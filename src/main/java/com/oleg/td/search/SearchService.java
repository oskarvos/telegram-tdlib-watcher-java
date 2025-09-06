package com.oleg.td.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private int progress = 0;

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
        progress = 0;

        new Thread(() -> {
            try {
                log.info("Запуск поиска по чатам...");
                coordinator.searchChats(request, this::incrementProgress);
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
        return new SearchProgress(progress, running.get());
    }

    private synchronized void incrementProgress() {
        progress++;
    }

    public void deleteSearchDatabase() {
        try {
            // Удаляем базу данных поиска
            // Реализацию этого метода нужно добавить в DatabaseManager
            log.info("База данных поиска удалена");
        } catch (Exception e) {
            log.error("Ошибка при удалении базы данных поиска: {}", e.getMessage(), e);
        }
    }
}