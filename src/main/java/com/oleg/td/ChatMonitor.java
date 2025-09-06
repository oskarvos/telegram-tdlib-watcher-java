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

        // Подготавливаем схему базы данных мониторинга И схему контрольных точек
        db.prepareMonitorSchema(); // Этот метод теперь также вызывает prepareCheckpointSchema()

        for (String chatRef : config.getMonitoredChats()) {
            try {
                long chatId = resolver.resolveFlexible(chatRef);
                // processAllMessages -> processNewMessages
                processNewMessages(chatId, config);
            } catch (Exception e) {
                log.error("Ошибка при обработке чата {}: {}", chatRef, e.getMessage());
            }
        }
        log.info("Мониторинг завершен - все сообщения обработаны");
    }

    /**
     * Обрабатывает только новые сообщения чата, начиная с последней сохраненной контрольной точки.
     * @param chatId ID чата для мониторинга
     * @param config Конфигурация мониторинга
     */
    private void processNewMessages(long chatId, MonitorConfig config) {
        try {
            // 1. Загружаем контрольную точку (ID последнего обработанного сообщения)
            long lastProcessedId = db.loadCheckpoint(chatId);
            log.info("Начинаем мониторинг чата {} с сообщения ID {}", chatId, lastProcessedId);

            String chatTitle = getChatTitle(chatId);
            // 2. Получаем сообщения, начиная с последнего обработанного ID
            List<MessageInfo> newMessages = getMessagesSinceId(chatId, lastProcessedId);

            if (newMessages.isEmpty()) {
                log.info("Новых сообщений для обработки в чате '{}' не найдено.", chatTitle);
                return;
            }

            int matchesCount = 0;
            long newLastProcessedId = lastProcessedId;

            for (MessageInfo message : newMessages) {
                // Обновляем максимальный ID, который мы видели в этой сессии
                if (message.getId() > newLastProcessedId) {
                    newLastProcessedId = message.getId();
                }

                if (message.getText() != null && !message.getText().isEmpty()) {
                    int messageMatches = checkMessageForMatches(chatId, chatTitle, message, config);
                    matchesCount += messageMatches;
                }
            }

            // 3. СОХРАНЯЕМ НОВУЮ КОНТРОЛЬНУЮ ТОЧКУ
            // Важно: делаем это даже если не нашли совпадений, чтобы в следующий раз не обрабатывать те же сообщения
            if (newLastProcessedId > lastProcessedId) {
                db.saveCheckpoint(chatId, newLastProcessedId);
                log.debug("Контрольная точка для чата {} обновлена на {}", chatId, newLastProcessedId);
            }

            log.info("Обработано {} новых сообщений, обнаружено {} совпадений в чате '{}'. Контрольная точка: {}",
                    newMessages.size(), matchesCount, chatTitle, newLastProcessedId);

        } catch (Exception e) {
            log.error("Ошибка при обработке чата {}: {}", chatId, e.getMessage());
        }
    }

    /**
     * Оптимизированный метод получения СООБЩЕНИЙ ПОСЛЕ указанного ID.
     * @param chatId ID чата
     * @param sinceMessageId ID сообщения, ПОСЛЕ которого нужно загружать историю.
     *                      Если 0, загружает с самого начала.
     * @return Список новых сообщений, отсортированных от старого к новому (в порядке возрастания ID).
     */
    private List<MessageInfo> getMessagesSinceId(long chatId, long sinceMessageId) {
        List<MessageInfo> messages = new ArrayList<>();
        int limit = 100;
        int requestCount = 0;
        long lastMessageId = sinceMessageId;
        boolean hasMoreMessages = true;

        log.debug("Начало получения сообщений для чата {} после ID {}", chatId, sinceMessageId);

        // Если sinceMessageId = 0, получаем с самого начала
        // Если sinceMessageId > 0, получаем сообщения, которые были после него
        long fromMessageId = 0; // Начинаем с самых новых сообщений
        long offset = 0;

        while (hasMoreMessages) {
            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", fromMessageId);
            req.put("offset", offset);
            req.put("limit", limit);
            req.put("only_local", false);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
            requestCount++;

            if ("messages".equals(resp.path("@type").asText()) && resp.path("messages").isArray()) {
                JsonNode messagesArray = resp.path("messages");

                if (messagesArray.size() == 0) {
                    hasMoreMessages = false;
                    continue;
                }

                int batchSize = 0;
                boolean foundTargetMessage = false;

                for (JsonNode msgNode : messagesArray) {
                    MessageInfo message = parseMessageInfo(msgNode);
                    if (message != null) {
                        // Если мы достигли сообщения с ID <= sinceMessageId, прекращаем обработку
                        if (sinceMessageId > 0 && message.getId() <= sinceMessageId) {
                            foundTargetMessage = true;
                            break;
                        }

                        messages.add(message);
                        lastMessageId = Math.max(lastMessageId, message.getId());
                        batchSize++;
                    }
                }

                log.debug("Получено {} сообщений из чата {}, всего: {}, последний ID: {}",
                        batchSize, chatId, messages.size(), lastMessageId);

                // Если нашли целевое сообщение или обработали весь чат
                if (foundTargetMessage || messagesArray.size() < limit) {
                    hasMoreMessages = false;
                } else {
                    // Для следующего запроса используем ID последнего полученного сообщения
                    fromMessageId = lastMessageId;
                    offset = -limit; // Смещение для получения более старых сообщений
                }

            } else {
                hasMoreMessages = false;
                log.warn("Неожиданный ответ от API для чата {}: {}", chatId, resp.path("@type").asText());
            }

            // Задержка для избежания flood wait
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                log.warn("Прервана задержка между запросами");
                break;
            }

            // Защита от бесконечного цикла
            if (requestCount > 100) {
                log.warn("Превышен лимит запросов для чата {}. Возможно, не все сообщения получены.", chatId);
                break;
            }
        }

        // Сортируем сообщения от старых к новым
        messages.sort((m1, m2) -> Long.compare(m1.getId(), m2.getId()));

        log.info("Завершено получение сообщений для чата {}: {} сообщений, {} запросов",
                chatId, messages.size(), requestCount);

        return messages;
    }

    private int checkMessageForMatches(long chatId, String chatTitle, MessageInfo message, MonitorConfig config) {
        int matchesInMessage = 0;

        for (String keyword : config.getKeywords()) {
            if (isMatch(message.getText(), keyword, config)) {

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