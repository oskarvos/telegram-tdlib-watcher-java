package com.oleg.td.search.core;

import com.oleg.td.dump.persistence.DumpDbManager; // Добавить этот импорт
import com.oleg.td.search.persistence.SearchDbManager;
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
    private final SearchDbManager searchDb; // Заменить DatabaseManager
    private final DumpDbManager dumpDb; // Добавить для работы с чекпоинтами

    public SearchService(SearchCoordinator coordinator, SearchDbManager searchDb, DumpDbManager dumpDb) {
        this.coordinator = coordinator;
        this.searchDb = searchDb;
        this.dumpDb = dumpDb;
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
        searchDb.clearSearchDatabase(chatId);   // очистить SEARCH-БД конкретного чата
        dumpDb.resetSearchCheckpoint(chatId); // сбросить чекпоинт в его DUMP-БД
        log.info("SEARCH-БД и чекпоинт поиска очищены для chatId={}", chatId);
    }

    public void deleteSearchDatabase() {
        log.info("Очистка всех SEARCH-БД и сброс чекпоинтов поиска во всех DUMP-БД");
        coordinator.stop();
        searchDb.clearAllSearchDatabases(); // очистка всех SEARCH-БД
        dumpDb.resetAllSearchCheckpoints(); // сброс всех чекпоинтов
    }
}