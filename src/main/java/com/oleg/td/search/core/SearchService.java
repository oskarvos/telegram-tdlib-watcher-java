package com.oleg.td.search.core;

import com.oleg.td.persistence.DatabaseManager;
import com.oleg.td.search.api.SearchProgress;
import com.oleg.td.search.api.SearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger processedMessages = new AtomicInteger(0);
    private final AtomicInteger foundMessages = new AtomicInteger(0);

    private final SearchCoordinator coordinator;
    private final DatabaseManager db;

    public SearchService(SearchCoordinator coordinator, DatabaseManager db) {
        this.coordinator = coordinator;
        this.db = db;
    }

    public synchronized void startSearch(SearchRequest request) {
        if (running.get()) {
            log.warn("Поиск уже выполняется");
            return;
        }
        running.set(true);
        processedMessages.set(0);
        foundMessages.set(0);

        new Thread(() -> {
            try {
                log.info("Запуск поиска по чатам...");
                coordinator.searchChats(request, this::incrementProgress, this::incrementFound);
                log.info("Поиск завершён");
            } catch (Exception e) {
                log.error("Ошибка при выполнении поиска: {}", e.getMessage(), e);
            } finally {
                running.set(false);
            }
        }).start();
    }

    public void stopSearch() {
        coordinator.stop();
        log.info("Получен сигнал остановки поиска");
    }

    public SearchProgress getProgress() {
        return new SearchProgress(processedMessages.get(), foundMessages.get(), running.get());
    }

    public synchronized void incrementProgress() {
        processedMessages.incrementAndGet();
    }

    public synchronized void incrementFound() {
        foundMessages.incrementAndGet();
    }

    public void deleteSearchDatabase(long chatId) {
        coordinator.stop();
        db.clearSearchDatabase(chatId);   // очистить SEARCH-БД конкретного чата
        db.resetSearchCheckpoint(chatId); // сбросить чекпоинт в его DUMP-БД
        log.info("SEARCH-БД и чекпоинт поиска очищены для chatId={}", chatId);
    }

    public void deleteSearchDatabase() {
        log.info("Очистка всех SEARCH-БД и сброс чекпоинтов поиска во всех DUMP-БД");
        coordinator.stop(); // на всякий случай, чтобы не было гонок
        db.clearSearchChatDatabases();
        try {
            // Полная очистка SEARCH и сброс search_last_message_id
            // (метод уже делает оба шага)
            //noinspection ConstantConditions
            com.oleg.td.persistence.DatabaseManager.class.getDeclaredMethod("clearSearchChatDatabases");
            // если метод есть, просто вызовем его:
            // (у вас уже внедрён DatabaseManager в SearchCoordinator -> получите через отражение/или прокиньте зависимость)
        } catch (Exception ignore) {
        }

        // Если SearchService не имеет прямого доступа к db, проще — добавьте зависимость:
        // private final DatabaseManager db;
        // и в конструкторе присвойте. Тогда здесь:
        // db.clearSearchChatDatabases();
        log.warn("Если лог выше не отработал — добавьте в SearchService зависимость DatabaseManager и вызовите db.clearSearchChatDatabases();");
    }

}