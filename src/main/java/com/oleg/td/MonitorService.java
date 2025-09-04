package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MonitorService {
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private final AtomicInteger matchesFound = new AtomicInteger(0);
    private final Set<Long> monitoredChatIds = ConcurrentHashMap.newKeySet();
    private String currentSearchTerm = "";
    private boolean caseSensitive = false;

    private final AuthFlow authFlow;
    private final ChatResolver resolver;
    private final UpdateRouter updateRouter;
    private final DatabaseManager db;

    public MonitorService(AuthFlow authFlow, ChatResolver resolver,
                          UpdateRouter updateRouter, DatabaseManager db) {
        this.authFlow = authFlow;
        this.resolver = resolver;
        this.updateRouter = updateRouter;
        this.db = db;
    }

    public synchronized void startMonitoring(MonitorRequest request) {
        if (monitoring.get()) {
            log.warn("Мониторинг уже запущен");
            return;
        }

        // Запускаем в отдельном потоке, чтобы не блокировать REST запрос
        new Thread(() -> {
            try {
                // Сначала подписываемся на обновления
                updateRouter.add(this::handleUpdate);
                log.info("Обработчик мониторинга добавлен в UpdateRouter");

                // Проверяем авторизацию без блокировки
                if (!authFlow.isAuthorized()) {
                    log.info("Требуется авторизация, запускаем процесс...");
                    authFlow.wireInto();
                    authFlow.authorizeBlocking();
                }

                if (!authFlow.isAuthorized()) {
                    log.error("Авторизация не удалась - мониторинг прерван");
                    return;
                }

                // Очищаем предыдущие данные
                monitoredChatIds.clear();
                matchesFound.set(0);
                currentSearchTerm = request.getSearchTerm();
                caseSensitive = request.isCaseSensitive();

                // Разрешаем чаты и подготавливаем схемы
                for (String chatRef : request.getChats()) {
                    try {
                        long chatId = resolver.resolveFlexible(chatRef.trim());
                        monitoredChatIds.add(chatId);
                        db.prepareSchema(chatId);
                        log.info("Добавлен чат для мониторинга: {} -> {}", chatRef, chatId);
                    } catch (Exception ex) {
                        log.error("Не удалось разрешить чат '{}': {}", chatRef, ex.getMessage());
                    }
                }

                if (monitoredChatIds.isEmpty()) {
                    log.error("Нет валидных чатов для мониторинга");
                    return;
                }

                monitoring.set(true);
                log.info("Мониторинг запущен для {} чатов, поиск: '{}'",
                        monitoredChatIds.size(), currentSearchTerm);

            } catch (Exception e) {
                log.error("Ошибка при запуске мониторинга: {}", e.getMessage(), e);
            }
        }, "MonitorStarter").start();
    }

    public void stopMonitoring() {
        if (monitoring.compareAndSet(true, false)) {
            log.info("Мониторинг остановлен");
        }
    }

    public MonitorStatus getStatus() {
        return new MonitorStatus(
                monitoring.get(),
                monitoredChatIds.size(),
                currentSearchTerm,
                matchesFound.get()
        );
    }

    private void handleUpdate(com.fasterxml.jackson.databind.node.ObjectNode update) {
        if (!monitoring.get()) {
            log.debug("Мониторинг не активен, пропускаем обновление");
            return;
        }

        String updateType = update.path("@type").asText();
        log.debug("Получено обновление: {}", updateType);

        if ("updateNewMessage".equals(updateType)) {
            processNewMessage(update.path("message"));
        }
    }

    private void processNewMessage(com.fasterxml.jackson.databind.JsonNode message) {
        try {
            long chatId = message.path("chat_id").asLong();
            log.debug("Новое сообщение в чате: {}", chatId);

            // Проверяем, отслеживаем ли мы этот чат
            if (!monitoredChatIds.contains(chatId)) {
                log.debug("Чат {} не отслеживается", chatId);
                return;
            }

            // Получаем текст сообщения
            String text = extractMessageText(message);
            if (text == null || text.isEmpty()) {
                log.debug("Сообщение без текста в чате {}", chatId);
                return;
            }

            log.info("Получено сообщение в чате {}: {}", chatId, text);

            // Поиск совпадения
            String searchText = caseSensitive ? text : text.toLowerCase();
            String searchTerm = caseSensitive ? currentSearchTerm : currentSearchTerm.toLowerCase();

            if (searchText.contains(searchTerm)) {
                long messageId = message.path("id").asLong();
                long timestamp = message.path("date").asLong();
                String senderId = message.path("sender_id").isMissingNode() ?
                        null : message.path("sender_id").toString();

                // Сохраняем в базу данных
                db.saveMonitorMatch(chatId, messageId, currentSearchTerm, text, timestamp, senderId);

                matchesFound.incrementAndGet();
                log.info("НАЙДЕНО СОВПАДЕНИЕ в чате {}: {}", chatId, text);
            } else {
                log.debug("Совпадение не найдено в сообщении: {}", text);
            }

        } catch (Exception e) {
            log.error("Ошибка обработки сообщения: {}", e.getMessage(), e);
        }
    }

    private String extractMessageText(com.fasterxml.jackson.databind.JsonNode message) {
        try {
            com.fasterxml.jackson.databind.JsonNode content = message.path("content");
            String contentType = content.path("@type").asText();

            if ("messageText".equals(contentType)) {
                return content.path("text").path("text").asText();
            } else if ("messagePhoto".equals(contentType)) {
                return content.path("caption").path("text").asText();
            } else if ("messageVideo".equals(contentType)) {
                return content.path("caption").path("text").asText();
            } else if ("messageDocument".equals(contentType)) {
                return content.path("caption").path("text").asText();
            } else if ("messageAnimation".equals(contentType)) {
                return content.path("caption").path("text").asText();
            }
        } catch (Exception e) {
            log.error("Ошибка извлечения текста сообщения: {}", e.getMessage());
        }
        return null;
    }
}