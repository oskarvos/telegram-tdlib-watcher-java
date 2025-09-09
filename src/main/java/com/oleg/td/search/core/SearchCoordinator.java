/* ===========================================================
 *  SearchCoordinator — обход истории чатов и сохранение совпадений
 *  - Работает с TDLib (TdJsonClient)
 *  - Ищет по сообщениям, сохраняет результаты ТОЛЬКО в SEARCH-БД
 *  - SEARCH-БД содержит одну пользовательскую таблицу search_results
 *  - Логика: на каждый новый запрос (новое слово) поиск выполняется
 *    по ВОЗМОЖНО ПОЛНОЙ истории чата (без чекпоинтов/пропусков).
 * =========================================================== */
package com.oleg.td.search.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.persistence.DatabaseManager;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import com.oleg.td.search.api.SearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Component
public class SearchCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SearchCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Long, String> userNameCache = new ConcurrentHashMap<>();
    private final Map<Long, String> chatTitleCache = new ConcurrentHashMap<>();

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;

    private volatile boolean stopRequested = false;

    public SearchCoordinator(TdJsonClient client, ChatResolver resolver, DatabaseManager db) {
        this.client = client;
        this.resolver = resolver;
        this.db = db;
    }

    /**
     * Поиск по всей истории выбранных чатов.
     * - Поддерживает подстроку или regex (чувствительность к регистру опциональна).
     * - Ищет в тексте сообщений и в caption медиасообщений.
     * - Пишет совпадения в SEARCH-БД (таблица search_results).
     */
    public void searchChats(SearchRequest request, Runnable progressCallback, Runnable foundCallback) {
        stopRequested = false;

        for (String chatRef : request.getChats()) {
            if (stopRequested) {
                log.info("Поиск прерван пользователем");
                break;
            }

            long chatId = resolver.resolveFlexible(chatRef.trim());
            String chatName = resolver.getChatTitle(chatId);
            log.info("Начинаем поиск в чате '{}' по ключу: {}", chatName, request.getKeyword());

            // Готовим схему SEARCH-БД (для данного чата)
            db.prepareSearchSchema(chatId);

            long fromMessageId = 0;          // старт с самых новых
            boolean reachedEnd = false;
            int totalMessagesProcessed = 0;
            final int MAX_MESSAGES = 100_000; // предохранитель на крайний случай

            while (!stopRequested && !reachedEnd && totalMessagesProcessed < MAX_MESSAGES) {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getChatHistory");
                req.put("chat_id", chatId);
                req.put("from_message_id", fromMessageId);
                req.put("offset", 0);
                req.put("limit", 100);
                req.put("only_local", false);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
                if (!"messages".equals(resp.path("@type").asText())) {
                    log.warn("Ответ TDLib отличен от 'messages': {}", resp.path("@type").asText());
                    break;
                }

                ArrayNode messages = (ArrayNode) resp.path("messages");
                if (messages == null || messages.size() == 0) {
                    log.info("Чат '{}': достигнут край истории (сообщений больше нет)", chatName);
                    reachedEnd = true;
                    break;
                }

                long oldestMessageId = Long.MAX_VALUE;

                for (JsonNode msg : messages) {
                    if (stopRequested) break;
                    if (totalMessagesProcessed >= MAX_MESSAGES) break;

                    try {
                        processMessageForSearch(chatId, chatName, msg, request, foundCallback);
                    } catch (Exception ex) {
                        long mid = msg.path("id").asLong();
                        log.error("Ошибка обработки сообщения {} из чата '{}': {}", mid, chatName, ex.getMessage(), ex);
                    } finally {
                        totalMessagesProcessed++;
                        if (progressCallback != null) progressCallback.run();
                    }

                    long mid = msg.path("id").asLong();
                    if (mid < oldestMessageId) oldestMessageId = mid;
                }

                if (!reachedEnd) {
                    if (oldestMessageId != Long.MAX_VALUE) {
                        fromMessageId = oldestMessageId;
                    } else {
                        break;
                    }

                    try {
                        Thread.sleep(100); // щадящая пауза
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("Поиск в чате '{}' завершён. Обработано сообщений: {}", chatName, totalMessagesProcessed);
        }
    }

    /** Проверка одного сообщения: текст + подписи у медиа */
    private void processMessageForSearch(long chatId, String chatTitle, JsonNode msg,
                                         SearchRequest request, Runnable foundCallback) {
        long messageId = msg.path("id").asLong();
        long date = msg.path("date").asLong(0);
        LocalDateTime messageDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(date), ZoneId.systemDefault());

        String senderId = msg.path("sender_id").isMissingNode() ? null : msg.path("sender_id").toString();
        String senderName = getSenderName(senderId);

        JsonNode content = msg.path("content");
        String ctype = content.path("@type").asText();

        // Берём основной текст, если это messageText
        StringBuilder sb = new StringBuilder();
        if ("messageText".equals(ctype)) {
            JsonNode ft = content.path("text");
            String text = ft.path("text").asText(null);
            if (text != null) sb.append(text);
        }

        // Добавляем caption для медиа (фото/видео/документы/аудио)
        appendCaptionIfAny(ctype, content, sb);

        String aggregatedText = sb.toString();
        if (containsKeyword(aggregatedText, request.getKeyword(), request.isCaseSensitive(), request.isUseRegex())) {
            db.saveSearchResultSearchDb(
                    chatId,
                    messageId,
                    messageDate,
                    request.getKeyword(),
                    aggregatedText,
                    senderId,
                    senderName
            );
            log.info("Найдено совпадение в чате '{}', сообщение {}: {}", chatTitle, messageId, aggregatedText);
            if (foundCallback != null) foundCallback.run();
        }
    }

    private void appendCaptionIfAny(String ctype, JsonNode content, StringBuilder sb) {
        // Унифицировано: если у контента есть поле "caption" с "text" — учитываем
        switch (ctype) {
            case "messagePhoto":
            case "messageVideo":
            case "messageDocument":
            case "messageAudio":
                JsonNode captionNode = content.path("caption");
                if (!captionNode.isMissingNode()) {
                    String c = captionNode.path("text").asText(null);
                    if (c != null && !c.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(c);
                    }
                }
                break;
            default:
                // другие типы нам не важны
        }
    }

    /** Подстрока/regex-поиск с поддержкой пустого ключа (0 символов) */
    private boolean containsKeyword(String text, String keyword, boolean caseSensitive, boolean useRegex) {
        // Пустой ключ — матчим любое НЕпустое сообщение/подпись (индексация)
        if (keyword == null || keyword.isEmpty()) {
            return text != null && !text.isBlank();
        }
        if (text == null) return false;

        if (useRegex) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(keyword, flags);
                return pattern.matcher(text).find();
            } catch (Exception e) {
                log.warn("Ошибка компиляции regex pattern: {}", e.getMessage());
                return false;
            }
        } else {
            return caseSensitive
                    ? text.contains(keyword)
                    : text.toLowerCase().contains(keyword.toLowerCase());
        }
    }

    /** Отображаемое имя отправителя */
    private String getSenderName(String senderIdJson) {
        if (senderIdJson == null || senderIdJson.isBlank()) return "";

        try {
            JsonNode n = MAPPER.readTree(senderIdJson);
            String type = n.path("@type").asText();

            // Пользователь
            if ("messageSenderUser".equals(type)) {
                long uid = n.path("user_id").asLong(0);
                if (uid == 0) return senderIdJson;

                String cached = userNameCache.get(uid);
                if (cached != null) return cached;

                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getUser");
                req.put("user_id", uid);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
                if ("user".equals(resp.path("@type").asText())) {
                    String first = resp.path("first_name").asText("");
                    String last  = resp.path("last_name").asText("");
                    String uname = resp.path("username").asText("");
                    String name  = (first + " " + last).trim();
                    if (name.isEmpty()) name = uname.isEmpty() ? String.valueOf(uid) : "@" + uname;

                    userNameCache.put(uid, name);
                    return name;
                }
            }

            // Канал/чат как отправитель
            if ("messageSenderChat".equals(type)) {
                long cid = n.path("chat_id").asLong(0);
                if (cid == 0) return senderIdJson;

                String cached = chatTitleCache.get(cid);
                if (cached != null) return cached;

                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getChat");
                req.put("chat_id", cid);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
                if ("chat".equals(resp.path("@type").asText())) {
                    String title = resp.path("title").asText("");
                    if (title == null || title.isBlank()) title = "chat_" + Math.abs(cid);
                    chatTitleCache.put(cid, title);
                    return title;
                }
            }
        } catch (Exception ignore) { }

        // Фолбэк — как было
        return senderIdJson;
    }

    /** Внешний запрос на остановку */
    public void stop() {
        stopRequested = true;
    }
}
