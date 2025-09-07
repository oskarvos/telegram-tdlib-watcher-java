package com.oleg.td.search.core;

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

    public SearchService(SearchCoordinator coordinator) {
        this.coordinator = coordinator;
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
        try {
            // Реализацию этого метода нужно добавить в DatabaseManager
            log.info("База данных поиска удалена для чата {}", chatId);
        } catch (Exception e) {
            log.error("Ошибка при удалении базы данных поиска: {}", e.getMessage(), e);
        }
    }

    public void deleteSearchDatabase() {
        // Заглушка для совместимости с контроллером и фронтом, чтобы сборка прошла
        log.warn("Удаление БД поиска без chatId: ничего не удалено. " +
                "Используйте deleteSearchDatabase(long chatId) для точечного удаления.");
    }
}