/* ===========================================================
 *  SearchCoordinator — обход истории чатов и сохранение совпадений
 *  - Работает с TDLib (TdJsonClient)
 *  - Ищет по сообщениям, сохраняет результаты ТОЛЬКО в SEARCH-БД
 *  - SEARCH-БД содержит одну пользовательскую таблицу search_results
 *  - Новая логика: при повторном поиске обрабатываем только новые сообщения.
 *    Чекпоинт хранится в DUMP-БД -> metadata("search_last_message_id").
 * =========================================================== */
package com.oleg.td.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.ChatResolver;
import com.oleg.td.DatabaseManager;
import com.oleg.td.TdJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

@Component
public class SearchCoordinator {
    /* ===================== Константы/зависимости ===================== */
    private static final Logger log = LoggerFactory.getLogger(SearchCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;

    private volatile boolean stopRequested = false;

    public SearchCoordinator(TdJsonClient client, ChatResolver resolver, DatabaseManager db) {
        this.client = client;
        this.resolver = resolver;
        this.db = db;
    }

    /* ===================== Основной цикл поиска по чатам ===================== */

    /**
     * Ищем в указанных чатах. Для каждого чата:
     *  - готовим SEARCH-БД (только search_results)
     *  - листаем историю батчами
     *  - проверяем каждое текстовое сообщение на совпадение
     *  - совпадения пишем в SEARCH-БД
     *  - ⚠ при повторном запуске: пропускаем всё, что <= последнего обработанного id
     *
     * @param request параметры поиска (чаты, keyword, флаги)
     * @param progressCallback колбэк на каждое обработанное сообщение
     * @param foundCallback колбэк на каждое найденное совпадение
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
            log.info("Начинаем поиск в чате '{}'", chatName);

            // --- готовим минимальную схему SEARCH-БД для этого чата
            db.prepareSearchSchema(chatId);

            // --- гарантируем наличие таблицы metadata в DUMP-БД (для чекпоинта поиска)
            db.prepareSchema(chatId);

            // --- читаем чекпоинт прошлого поиска из DUMP-БД
            long lastSearchProcessedId = 0L;
            try {
                String v = db.loadMetadata(chatId, "search_last_message_id");
                if (v != null && !v.isBlank()) {
                    lastSearchProcessedId = Long.parseLong(v.trim());
                }
            } catch (Exception ignore) {
                // если парсинг не удался — начнём с нуля
            }
            if (lastSearchProcessedId > 0) {
                log.info("Чат '{}': продолжим поиск с сообщений новее id={}", chatName, lastSearchProcessedId);
            }

            long fromMessageId = 0;                 // начинаем с самых новых
            boolean reachedEnd = false;
            int totalMessagesProcessed = 0;
            final int MAX_MESSAGES = 10000;         // предохранитель
            long maxProcessedId = 0L;               // максимальный id, который реально обработали в этом запуске

            while (!stopRequested && !reachedEnd && totalMessagesProcessed < MAX_MESSAGES) {
                // --- запрос истории TDLib
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

                // --- обработка пачки сообщений (идут от новых к старым)
                for (JsonNode msg : messages) {
                    if (stopRequested) break;
                    if (totalMessagesProcessed >= MAX_MESSAGES) break;

                    long mid = msg.path("id").asLong();

                    // если дошли до уже обработанных ранее — прекращаем
                    if (lastSearchProcessedId > 0 && mid <= lastSearchProcessedId) {
                        log.info("Чат '{}': достигли уже обработанных сообщений (mid={} <= {}), стоп",
                                chatName, mid, lastSearchProcessedId);
                        reachedEnd = true;
                        break;
                    }

                    // фиксируем макс. id, который мы реально увидели в этом запуске
                    if (mid > maxProcessedId) {
                        maxProcessedId = mid;
                    }

                    try {
                        processMessageForSearch(chatId, chatName, msg, request, foundCallback);
                        totalMessagesProcessed++;
                        if (progressCallback != null) progressCallback.run();
                    } catch (Exception ex) {
                        log.error("Ошибка обработки сообщения {} из чата '{}': {}", mid, chatName, ex.getMessage(), ex);
                    }
                }

                // --- смещаемся к более старым сообщениям
                if (!reachedEnd && totalMessagesProcessed < MAX_MESSAGES) {
                    long oldestMessageId = Long.MAX_VALUE;
                    for (JsonNode msg : messages) {
                        long msgId = msg.path("id").asLong();
                        if (msgId < oldestMessageId) oldestMessageId = msgId;
                    }
                    if (oldestMessageId != Long.MAX_VALUE) {
                        fromMessageId = oldestMessageId;
                    } else {
                        break;
                    }

                    try {
                        Thread.sleep(100); // щадящая пауза между запросами
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // --- если в этом запуске действительно были обработаны новые сообщения — обновим чекпоинт
            if (maxProcessedId > 0) {
                long newCheckpoint = Math.max(lastSearchProcessedId, maxProcessedId);
                db.saveMetadata(chatId, "search_last_message_id", Long.toString(newCheckpoint));
                log.info("Чат '{}': обновили чекпоинт поиска search_last_message_id={}", chatName, newCheckpoint);
            } else {
                log.info("Чат '{}': новых сообщений не найдено, чекпоинт не меняем", chatName);
            }

            log.info("Поиск в чате '{}' завершён. Обработано сообщений: {}", chatName, totalMessagesProcessed);
        }
    }

    /* ===================== Обработка одного сообщения ===================== */

    /**
     * Проверка текстового сообщения на совпадение и сохранение результата в SEARCH-БД.
     */
    private void processMessageForSearch(long chatId, String chatTitle, JsonNode msg,
                                         SearchRequest request, Runnable foundCallback) {
        long messageId = msg.path("id").asLong();
        long date = msg.path("date").asLong(0);
        LocalDateTime messageDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(date), ZoneId.systemDefault());

        String senderId = msg.path("sender_id").isMissingNode() ? null : msg.path("sender_id").toString();
        String senderName = getSenderName(senderId);

        JsonNode content = msg.path("content");
        String ctype = content.path("@type").asText();

        String messageText = "";
        if ("messageText".equals(ctype)) {
            JsonNode ft = content.path("text");
            messageText = ft.path("text").asText(null);
        }

        if (containsKeyword(messageText, request.getKeyword(), request.isCaseSensitive(), request.isUseRegex())) {
            db.saveSearchResultSearchDb(chatId, messageId, messageDate,
                    request.getKeyword(), messageText, senderId, senderName);
            log.info("Найдено совпадение в чате '{}', сообщение {}: {}", chatTitle, messageId, messageText);
            if (foundCallback != null) foundCallback.run();
        }
    }

    /* ===================== Поисковая логика по строкам ===================== */

    /** Проверка содержания на совпадение по подстроке или regex */
    private boolean containsKeyword(String text, String keyword, boolean caseSensitive, boolean useRegex) {
        if (text == null || keyword == null || keyword.isEmpty()) return false;

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

    /** Получение отображаемого имени отправителя (пока отдаём идентификатор) */
    private String getSenderName(String senderId) {
        return senderId;
    }

    /* ===================== Управление процессом ===================== */

    /** Запрос на остановку внешним кодом */
    public void stop() {
        stopRequested = true;
    }
}
