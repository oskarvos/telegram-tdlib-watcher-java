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

    public MonitorService(AuthFlow authFlow, ChatResolver resolver, UpdateRouter updateRouter) {
        this.authFlow = authFlow;
        this.resolver = resolver;
        this.updateRouter = updateRouter;
    }

    public synchronized void startMonitoring(MonitorRequest request) {
        if (monitoring.get()) {
            log.warn("Мониторинг уже запущен");
            return;
        }

        try {
            // Авторизация
            authFlow.wireInto();
            authFlow.authorizeBlocking();

            if (!authFlow.isAuthorized()) {
                log.error("Авторизация не удалась - мониторинг прерван");
                return;
            }

            // Очищаем предыдущие данные
            monitoredChatIds.clear();
            matchesFound.set(0);
            currentSearchTerm = request.getSearchTerm();
            caseSensitive = request.isCaseSensitive();

            // Разрешаем чаты
            for (String chatRef : request.getChats()) {
                try {
                    long chatId = resolver.resolveFlexible(chatRef.trim());
                    monitoredChatIds.add(chatId);
                    log.info("Добавлен чат для мониторинга: {} -> {}", chatRef, chatId);
                } catch (Exception ex) {
                    log.error("Не удалось разрешить чат '{}': {}", chatRef, ex.getMessage());
                }
            }

            if (monitoredChatIds.isEmpty()) {
                log.error("Нет валидных чатов для мониторинга");
                return;
            }

            // Подписываемся на обновления сообщений
            updateRouter.add(this::handleUpdate);

            monitoring.set(true);
            log.info("Мониторинг запущен для {} чатов, поиск: '{}'",
                    monitoredChatIds.size(), currentSearchTerm);

        } catch (Exception e) {
            log.error("Ошибка при запуске мониторинга: {}", e.getMessage(), e);
        }
    }

    public void stopMonitoring() {
        if (monitoring.compareAndSet(true, false)) {
            // Можно добавить логику отписки от конкретного обработчика
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
        if (!monitoring.get()) return;

        String updateType = update.path("@type").asText();
        if ("updateNewMessage".equals(updateType)) {
            processNewMessage(update.path("message"));
        }
    }

    private void processNewMessage(com.fasterxml.jackson.databind.JsonNode message) {
        try {
            long chatId = message.path("chat_id").asLong();

            // Проверяем, отслеживаем ли мы этот чат
            if (!monitoredChatIds.contains(chatId)) {
                return;
            }

            // Получаем текст сообщения
            String text = extractMessageText(message);
            if (text == null || text.isEmpty()) {
                return;
            }

            // Поиск совпадения
            String searchText = caseSensitive ? text : text.toLowerCase();
            String searchTerm = caseSensitive ? currentSearchTerm : currentSearchTerm.toLowerCase();

            if (searchText.contains(searchTerm)) {
                matchesFound.incrementAndGet();
                log.info("НАЙДЕНО СОВПАДЕНИЕ в чате {}: {}", chatId, text);

                // Здесь можно добавить дополнительную логику, например:
                // - сохранение в базу данных
                // - отправку уведомления
                // - логирование в файл
            }

        } catch (Exception e) {
            log.error("Ошибка обработки сообщения: {}", e.getMessage());
        }
    }

    private String extractMessageText(com.fasterxml.jackson.databind.JsonNode message) {
        com.fasterxml.jackson.databind.JsonNode content = message.path("content");
        String contentType = content.path("@type").asText();

        if ("messageText".equals(contentType)) {
            return content.path("text").path("text").asText();
        }
        // Можно добавить обработку других типов контента
        return null;
    }
}