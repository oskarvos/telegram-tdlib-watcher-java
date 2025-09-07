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

            // Подготавливаем схему для данного чата
            db.prepareSchema(chatId);

            long fromMessageId = 0;
            boolean reachedEnd = false;
            int totalMessagesProcessed = 0;
            final int MAX_MESSAGES = 10000;

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

                for (JsonNode msg : messages) {
                    if (stopRequested) break;
                    if (totalMessagesProcessed >= MAX_MESSAGES) break;

                    long mid = msg.path("id").asLong();

                    try {
                        processMessageForSearch(chatId, chatName, msg, request, foundCallback);
                        totalMessagesProcessed++;
                        if (progressCallback != null) progressCallback.run();
                    } catch (Exception ex) {
                        log.error("Ошибка обработки сообщения {} из чата '{}': {}", mid, chatName, ex.getMessage(), ex);
                    }
                }

                if (!reachedEnd && totalMessagesProcessed < MAX_MESSAGES) {
                    long oldestMessageId = Long.MAX_VALUE;
                    for (JsonNode msg : messages) {
                        long msgId = msg.path("id").asLong();
                        if (msgId < oldestMessageId) {
                            oldestMessageId = msgId;
                        }
                    }

                    if (oldestMessageId != Long.MAX_VALUE) {
                        fromMessageId = oldestMessageId;
                    } else {
                        break;
                    }

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("Поиск в чате '{}' завершён. Обработано сообщений: {}", chatName, totalMessagesProcessed);
        }
    }

    private void processMessageForSearch(long chatId, String chatTitle, JsonNode msg, SearchRequest request, Runnable foundCallback) {
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

        // Проверяем, содержит ли сообщение ключевое слово
        if (containsKeyword(messageText, request.getKeyword(), request.isCaseSensitive(), request.isUseRegex())) {
            // Сохраняем результат поиска
            db.saveSearchResult(chatId, messageId, messageDate,
                    request.getKeyword(), messageText, senderId, senderName);
            log.info("Найдено совпадение в чате '{}', сообщение {}: {}", chatTitle, messageId, messageText);
            if (foundCallback != null) foundCallback.run();
        }
    }

    private boolean containsKeyword(String text, String keyword, boolean caseSensitive, boolean useRegex) {
        if (text == null || keyword == null || keyword.isEmpty()) {
            return false;
        }

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
            if (caseSensitive) {
                return text.contains(keyword);
            } else {
                return text.toLowerCase().contains(keyword.toLowerCase());
            }
        }
    }

    private String getSenderName(String senderId) {
        // Здесь можно реализовать логику получения имени отправителя
        // Пока возвращаем идентификатор отправителя
        return senderId;
    }

    public void stop() {
        stopRequested = true;
    }
}