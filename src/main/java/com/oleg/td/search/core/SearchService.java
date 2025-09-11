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
    private volatile Thread searchThread;

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

        searchThread = new Thread(() -> {
            try {
                log.info("Запуск поиска по чатам...");
                coordinator.searchChats(request, this::incrementProgress, this::incrementFound);
                log.info("Поиск завершён");
            } catch (Exception e) {
                log.error("Ошибка при выполнении поиска: {}", e.getMessage(), e);
            } finally {
                running.set(false);
                searchThread = null; // ← важно
            }
        }, "search-thread");
        searchThread.start();
    }

    public void stopSearch() {
        coordinator.stop();
        Thread t = searchThread;
        if (t != null) t.interrupt(); // прервём возможный sleep в координаторе
        log.info("Получен сигнал остановки поиска");
    }

    /**
     * Остановить и дождаться завершения потока (для безопасной очистки БД).
     */
    public void stopSearchAndWait(long timeoutMs) {
        stopSearch();
        Thread t = searchThread;
        if (t != null) {
            try {
                t.join(Math.max(0, timeoutMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Поиск остановлен (готово к очистке БД)");
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
}