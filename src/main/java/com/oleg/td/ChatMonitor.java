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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Component
public class ChatMonitor {
    private static final Logger log = LoggerFactory.getLogger(ChatMonitor.class);

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;
    private final ScheduledExecutorService scheduler;

    private MonitorConfig currentConfig;
    private boolean monitoring = false;

    public ChatMonitor(TdJsonClient client, ChatResolver resolver, DatabaseManager db) {
        this.client = client;
        this.resolver = resolver;
        this.db = db;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void startMonitoring(MonitorConfig config) {
        if (monitoring) {
            stopMonitoring();
        }

        this.currentConfig = config;
        this.monitoring = true;

        log.info("Запуск мониторинга чатов: {}", config.getMonitoredChats());

        // Первая проверка сразу
        checkChats();

        // Периодическая проверка
        int intervalMinutes = Math.max(1, config.getCheckIntervalMinutes());
        scheduler.scheduleAtFixedRate(this::checkChats, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    public void stopMonitoring() {
        monitoring = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Мониторинг остановлен");
    }

    private void checkChats() {
        if (!monitoring || currentConfig == null) {
            return;
        }

        log.debug("Начало проверки чатов на совпадения");

        for (String chatRef : currentConfig.getMonitoredChats()) {
            try {
                long chatId = resolver.resolveFlexible(chatRef);
                checkChatForMatches(chatId);
            } catch (Exception e) {
                log.error("Ошибка при проверке чата {}: {}", chatRef, e.getMessage());
            }
        }
    }

    private void checkChatForMatches(long chatId) {
        try {
            // Получаем информацию о чате
            String chatTitle = getChatTitle(chatId);

            // Получаем последние сообщения
            List<MessageInfo> recentMessages = getRecentMessages(chatId, 50); // Проверяем последние 50 сообщений

            for (MessageInfo message : recentMessages) {
                checkMessageForMatches(chatId, chatTitle, message);
            }
        } catch (Exception e) {
            log.error("Ошибка при проверке чата {}: {}", chatId, e.getMessage());
        }
    }

    private void checkMessageForMatches(long chatId, String chatTitle, MessageInfo message) {
        if (message.getText() == null || message.getText().isEmpty()) {
            return;
        }

        for (String keyword : currentConfig.getKeywords()) {
            if (isMatch(message.getText(), keyword)) {
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
                log.info("Найдено совпадение: чат '{}', ключевое слово '{}'", chatTitle, keyword);
            }
        }
    }

    private boolean isMatch(String text, String keyword) {
        if (currentConfig.isRegexMode()) {
            try {
                int flags = currentConfig.isCaseSensitive() ? 0 : Pattern.CASE_INSENSITIVE;
                Pattern pattern = Pattern.compile(keyword, flags);
                return pattern.matcher(text).find();
            } catch (PatternSyntaxException e) {
                log.warn("Некорректное регулярное выражение: {}", keyword);
                return false;
            }
        } else {
            if (currentConfig.isCaseSensitive()) {
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

    private List<MessageInfo> getRecentMessages(long chatId, int limit) {
        List<MessageInfo> messages = new ArrayList<>();

        ObjectNode req = Utils.obj("getChatHistory");
        req.put("chat_id", chatId);
        req.put("from_message_id", 0);
        req.put("offset", 0);
        req.put("limit", limit);
        req.put("only_local", false);

        ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);

        if ("messages".equals(resp.path("@type").asText()) && resp.path("messages").isArray()) {
            for (JsonNode msgNode : resp.path("messages")) {
                MessageInfo message = parseMessageInfo(msgNode);
                if (message != null) {
                    messages.add(message);
                }
            }
        }

        return messages;
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

    public boolean isMonitoring() {
        return monitoring;
    }

    public MonitorConfig getCurrentConfig() {
        return currentConfig;
    }
}