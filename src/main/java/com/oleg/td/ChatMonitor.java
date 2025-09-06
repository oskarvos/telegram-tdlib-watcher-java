package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class ChatMonitor {
    private static final Logger log = LoggerFactory.getLogger(ChatMonitor.class);

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;

    public ChatMonitor(TdJsonClient client, ChatResolver resolver, DatabaseManager db) {
        this.client = client;
        this.resolver = resolver;
        this.db = db;
    }

    public void startMonitoring(MonitorConfig config) {
        log.info("Запуск мониторинга чатов: {}", config.getMonitoredChats());

        // Подготавливаем схему базы данных мониторинга
        db.prepareMonitorSchema();

        for (String chatRef : config.getMonitoredChats()) {
            try {
                long chatId = resolver.resolveFlexible(chatRef);
                processAllMessages(chatId, config);
            } catch (Exception e) {
                log.error("Ошибка при обработке чата {}: {}", chatRef, e.getMessage());
            }
        }

        log.info("Мониторинг завершен - все сообщения обработаны");
    }

    private void processAllMessages(long chatId, MonitorConfig config) {
        try {
            String chatTitle = getChatTitle(chatId);
            List<MessageInfo> allMessages = getAllMessagesOptimized(chatId);

            int matchesCount = 0;

            for (MessageInfo message : allMessages) {
                if (message.getText() != null && !message.getText().isEmpty()) {
                    int messageMatches = checkMessageForMatches(chatId, chatTitle, message, config);
                    matchesCount += messageMatches;
                }
            }

            log.info("Обработано {} сообщений, обнаружено {} совпадений в чате '{}'",
                    allMessages.size(), matchesCount, chatTitle);

        } catch (Exception e) {
            log.error("Ошибка при обработке чата {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Оптимизированный метод получения всех сообщений из чата
     * с правильной пагинацией и защитой от flood wait
     */
    private List<MessageInfo> getAllMessagesOptimized(long chatId) {
        List<MessageInfo> messages = new ArrayList<>();
        long fromMessageId = 0;
        int limit = 100;
        boolean hasMoreMessages = true;
        int requestCount = 0;

        log.debug("Начало получения сообщений для чата {}", chatId);

        while (hasMoreMessages) {
            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", fromMessageId);
            req.put("offset", 0);
            req.put("limit", limit);
            req.put("only_local", false);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
            requestCount++;

            if ("messages".equals(resp.path("@type").asText()) && resp.path("messages").isArray()) {
                JsonNode messagesArray = resp.path("messages");

                // КЛЮЧЕВОЕ УСЛОВИЕ: останавливаемся только при пустом массиве
                if (messagesArray.size() == 0) {
                    hasMoreMessages = false;
                    log.debug("Получен пустой массив - все сообщения получены");
                    continue;
                }

                int batchSize = 0;
                long lastMessageId = fromMessageId;

                for (JsonNode msgNode : messagesArray) {
                    MessageInfo message = parseMessageInfo(msgNode);
                    if (message != null) {
                        messages.add(message);
                        lastMessageId = message.getId();
                        batchSize++;
                    }
                }

                log.debug("Получено {} сообщений из чата {}, всего: {}, последний ID: {}",
                        batchSize, chatId, messages.size(), lastMessageId);

                // Проверяем на зацикливание
                if (lastMessageId == fromMessageId) {
                    hasMoreMessages = false;
                    log.debug("ID сообщения не изменился - завершаем");
                } else {
                    // Продолжаем с ID последнего полученного сообщения
                    fromMessageId = lastMessageId;
                }

                // Задержка для избежания flood wait
                if (hasMoreMessages) {
                    try {
                        int delayMs = Math.min(1000, 100 + (requestCount * 20));
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        log.warn("Прервана задержка между запросами");
                        break;
                    }
                }

            } else {
                hasMoreMessages = false;
                log.warn("Неожиданный ответ от API для чата {}: {}", chatId, resp.path("@type").asText());
            }

            // Защита от бесконечного цикла
            if (requestCount > 1000) {
                log.warn("Превышен лимит запросов для чата {}. Возможно, не все сообщения получены.", chatId);
                break;
            }
        }

        log.info("Завершено получение сообщений для чата {}: {} сообщений, {} запросов",
                chatId, messages.size(), requestCount);

        return messages;
    }


    private int checkMessageForMatches(long chatId, String chatTitle, MessageInfo message, MonitorConfig config) {
        int matchesInMessage = 0;

        for (String keyword : config.getKeywords()) {
            if (isMatch(message.getText(), keyword, config)) {
                // Проверяем, нет ли уже такого результата в БД
                List<MonitorResult> existingResults = db.getMonitorResultsByChatAndMessage(
                        chatId, message.getId(), keyword);

                if (existingResults.isEmpty()) {
                    MonitorResult result = new MonitorResult(
                            chatId,
                            chatTitle,
                            message.getId(),
                            message.getDate(),
                            keyword,
                            message.getText(),
                            message.getSenderId(),
                            message.getSenderName()
                    );

                    db.saveMonitorResult(result);
                    matchesInMessage++;
                    log.debug("Сохранено совпадение: чат '{}', ключевое слово '{}'", chatTitle, keyword);
                } else {
                    log.debug("Совпадение уже существует: чат '{}', сообщение {}, ключ '{}'",
                            chatTitle, message.getId(), keyword);
                }
            }
        }

        return matchesInMessage;
    }

    private boolean isMatch(String text, String keyword, MonitorConfig config) {
        if (config.isRegexMode()) {
            try {
                int flags = config.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(keyword, flags);
                return pattern.matcher(text).find();
            } catch (PatternSyntaxException e) {
                log.warn("Некорректное регулярное выражение: {}", keyword);
                return false;
            }
        } else {
            if (config.isCaseSensitive()) {
                return text.contains(keyword);
            } else {
                return text.toLowerCase().contains(keyword.toLowerCase());
            }
        }
    }

    private String getChatTitle(long chatId) {
        ObjectNode req = Utils.obj("getChat");
        req.put("chat_id", chatId);

        ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
        if ("chat".equals(resp.path("@type").asText())) {
            return resp.path("title").asText("Unknown");
        }
        return "Unknown";
    }

    private MessageInfo parseMessageInfo(JsonNode msgNode) {
        try {
            MessageInfo message = new MessageInfo();
            message.setId(msgNode.path("id").asLong());
            message.setDate(LocalDateTime.ofEpochSecond(msgNode.path("date").asLong(), 0, ZoneOffset.UTC));

            JsonNode content = msgNode.path("content");
            if ("messageText".equals(content.path("@type").asText())) {
                message.setText(content.path("text").path("text").asText());
            }

            JsonNode sender = msgNode.path("sender_id");
            if (!sender.isMissingNode()) {
                message.setSenderId(sender.toString());
                message.setSenderName(getSenderName(sender));
            }

            return message;
        } catch (Exception e) {
            log.warn("Ошибка парсинга сообщения: {}", e.getMessage());
            return null;
        }
    }

    private String getSenderName(JsonNode senderNode) {
        String type = senderNode.path("@type").asText();
        if ("messageSenderUser".equals(type)) {
            long userId = senderNode.path("user_id").asLong();
            return getUserName(userId);
        } else if ("messageSenderChat".equals(type)) {
            long chatId = senderNode.path("chat_id").asLong();
            return getChatTitle(chatId);
        }
        return "Unknown";
    }

    private String getUserName(long userId) {
        ObjectNode req = Utils.obj("getUser");
        req.put("user_id", userId);

        ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
        if ("user".equals(resp.path("@type").asText())) {
            String firstName = resp.path("first_name").asText("");
            String lastName = resp.path("last_name").asText("");
            return (firstName + " " + lastName).trim();
        }
        return "User#" + userId;
    }
}