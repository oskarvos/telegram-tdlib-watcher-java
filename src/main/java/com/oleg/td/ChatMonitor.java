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

        for (String chatRef : config.getMonitoredChats()) {
            try {
                long chatId = resolver.resolveFlexible(chatRef);
                // Убедимся, что схема базы данных подготовлена
                db.prepareSchema(chatId);
                processAllMessages(chatId, config);
            } catch (Exception e) {
                log.error("Ошибка при обработке чата {}: {}", chatRef, e.getMessage());
            }
        }

        log.info("Мониторинг завершен - все сообщения обработаны");
    }

    public void startIncrementalMonitoring(MonitorConfig config) {
        log.info("Запуск инкрементального мониторинга чатов: {}", config.getMonitoredChats());

        for (String chatRef : config.getMonitoredChats()) {
            try {
                long chatId = resolver.resolveFlexible(chatRef);
                // Убедимся, что схема базы данных подготовлена
                db.prepareSchema(chatId);
                processNewMessages(chatId, config);
            } catch (Exception e) {
                log.error("Ошибка при обработке чата {}: {}", chatRef, e.getMessage());
            }
        }

        log.info("Инкрементальный мониторинг завершен");
    }

    private void processAllMessages(long chatId, MonitorConfig config) {
        try {
            String chatTitle = getChatTitle(chatId);
            List<MessageInfo> allMessages = getAllMessages(chatId);

            int matchesCount = 0;

            for (MessageInfo message : allMessages) {
                if (message.getText() != null && !message.getText().isEmpty()) {
                    int messageMatches = checkMessageForMatches(chatId, chatTitle, message, config);
                    matchesCount += messageMatches;

                    // Сохраняем сообщение в базу для последующего инкрементального обновления
                    saveMessageToDatabase(chatId, message);
                }
            }

            log.info("Обработано {}, обнаружено {} в чате '{}'", allMessages.size(), matchesCount, chatTitle);

        } catch (Exception e) {
            log.error("Ошибка при обработке чата {}: {}", chatId, e.getMessage());
        }
    }

    private void processNewMessages(long chatId, MonitorConfig config) {
        try {
            String chatTitle = getChatTitle(chatId);
            long lastMessageId = db.getLastSavedMessageId(chatId);

            log.info("Начинаем инкрементальную обработку чата '{}', последнее сообщение ID: {}", chatTitle, lastMessageId);

            List<MessageInfo> newMessages = getMessagesSince(chatId, lastMessageId);
            int matchesCount = 0;

            for (MessageInfo message : newMessages) {
                if (message.getText() != null && !message.getText().isEmpty()) {
                    int messageMatches = checkMessageForMatches(chatId, chatTitle, message, config);
                    matchesCount += messageMatches;

                    // Сохраняем сообщение в базу для последующего инкрементального обновления
                    saveMessageToDatabase(chatId, message);
                }
            }

            log.info("Обработано {} новых сообщений, обнаружено {} совпадений в чате '{}'",
                    newMessages.size(), matchesCount, chatTitle);

        } catch (Exception e) {
            log.error("Ошибка при инкрементальной обработке чата {}: {}", chatId, e.getMessage());
        }
    }

    private List<MessageInfo> getAllMessages(long chatId) {
        List<MessageInfo> messages = new ArrayList<>();
        long fromMessageId = 0;
        boolean hasMoreMessages = true;

        while (hasMoreMessages) {
            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", fromMessageId);
            req.put("offset", 0);
            req.put("limit", 100);
            req.put("only_local", false);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);

            if ("messages".equals(resp.path("@type").asText()) && resp.path("messages").isArray()) {
                JsonNode messagesArray = resp.path("messages");

                if (messagesArray.size() == 0) {
                    hasMoreMessages = false;
                    continue;
                }

                for (JsonNode msgNode : messagesArray) {
                    MessageInfo message = parseMessageInfo(msgNode);
                    if (message != null) {
                        messages.add(message);
                        fromMessageId = message.getId();
                    }
                }
            } else {
                hasMoreMessages = false;
            }
        }

        return messages;
    }

    private List<MessageInfo> getMessagesSince(long chatId, long sinceMessageId) {
        List<MessageInfo> messages = new ArrayList<>();
        long fromMessageId = 0;
        boolean hasMoreMessages = true;
        boolean reachedOldMessages = false;

        while (hasMoreMessages && !reachedOldMessages) {
            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", fromMessageId);
            req.put("offset", 0);
            req.put("limit", 100);
            req.put("only_local", false);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);

            if ("messages".equals(resp.path("@type").asText()) && resp.path("messages").isArray()) {
                JsonNode messagesArray = resp.path("messages");

                if (messagesArray.size() == 0) {
                    hasMoreMessages = false;
                    continue;
                }

                for (JsonNode msgNode : messagesArray) {
                    MessageInfo message = parseMessageInfo(msgNode);
                    if (message != null) {
                        if (message.getId() <= sinceMessageId) {
                            reachedOldMessages = true;
                            break;
                        }

                        messages.add(message);
                        fromMessageId = message.getId();
                    }
                }
            } else {
                hasMoreMessages = false;
            }
        }

        return messages;
    }

    private void saveMessageToDatabase(long chatId, MessageInfo message) {
        try {
            db.saveMessage(
                    chatId,
                    message.getId(),
                    message.getDate().toEpochSecond(ZoneOffset.UTC),
                    message.getSenderId(),
                    null, // reply_to - можно добавить при необходимости
                    message.getText()
            );
        } catch (Exception e) {
            log.warn("Не удалось сохранить сообщение {} в базу: {}", message.getId(), e.getMessage());
        }
    }

    private int checkMessageForMatches(long chatId, String chatTitle, MessageInfo message, MonitorConfig config) {
        int matchesInMessage = 0;

        for (String keyword : config.getKeywords()) {
            if (isMatch(message.getText(), keyword, config)) {
                // Проверяем, не было ли уже такого совпадения
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
                    log.debug("Сохранено новое совпадение: чат '{}', ключевое слово '{}'", chatTitle, keyword);
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