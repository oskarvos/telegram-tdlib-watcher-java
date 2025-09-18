package com.oleg.td.dump.core;

import com.oleg.td.dump.api.DumpProgress;
import com.oleg.td.dump.api.DumpRequest;
import com.oleg.td.integrations.tdlibs.AuthFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сервис-обёртка дампа: управляет потоком, считает прогресс и метрики.
 */
@Service
public class DumpService {
    private static final Logger log = LoggerFactory.getLogger(DumpService.class);

    private final AtomicBoolean running = new AtomicBoolean(false); // признак выполнения

    private final AtomicInteger processed     = new AtomicInteger(0); // обработано сообщений
    private final AtomicInteger savedMessages = new AtomicInteger(0); // сохранено текстов
    private final AtomicInteger savedPhotos   = new AtomicInteger(0); // сохранено фото
    private final AtomicInteger savedVideos   = new AtomicInteger(0); // сохранено видео
    private final AtomicInteger savedAudio    = new AtomicInteger(0); // сохранено аудио
    private final AtomicInteger savedDocs     = new AtomicInteger(0); // сохранено документов
    private final AtomicInteger savedLinks    = new AtomicInteger(0); // сохранено ссылок

    private final ConcurrentHashMap<String, AtomicInteger> docsByExt = new ConcurrentHashMap<>(); // счётчик по расширениям

    private final AuthFlow authFlow;               // авторизация TDLib
    private final ChatDumpCoordinator coordinator; // координатор дампа

    public DumpService(AuthFlow authFlow,
                       ChatDumpCoordinator coordinator) {
        this.authFlow = authFlow;
        this.coordinator = coordinator;
    }

    /** Старт дампа (без повторного запуска при уже работающем процессе). */
    public synchronized void startDump(DumpRequest request) {
        if (running.get()) { log.warn("Дамп уже выполняется"); return; }

        running.set(true);
        processed.set(0);
        savedMessages.set(0);
        savedPhotos.set(0);
        savedVideos.set(0);
        savedAudio.set(0);
        savedDocs.set(0);
        savedLinks.set(0);
        docsByExt.clear();

        new Thread(() -> {
            try {
                if (!authFlow.isAuthorized()) {
                    log.error("Авторизация не выполнена — дамп прерван");
                    return;
                }

                log.info("Запуск дампа чатов…");
                coordinator.dumpChats(request, new DumpListener() {
                    @Override public void onProgress() { processed.incrementAndGet(); }
                    @Override public void onSavedMessage() { savedMessages.incrementAndGet(); }
                    @Override public void onSavedPhoto() { savedPhotos.incrementAndGet(); }
                    @Override public void onSavedVideo() { savedVideos.incrementAndGet(); }
                    @Override public void onSavedAudio() { savedAudio.incrementAndGet(); }
                    @Override public void onSavedDocument(String ext) {
                        savedDocs.incrementAndGet();
                        docsByExt.computeIfAbsent(
                                (ext == null || ext.isBlank()) ? "unknown" : ext.toLowerCase(),
                                k -> new AtomicInteger(0)
                        ).incrementAndGet();
                    }
                    @Override public void onSavedLinks(int count) { savedLinks.addAndGet(Math.max(0, count)); }
                });
                log.info("Дамп завершён");
            } catch (Exception e) {
                log.error("Ошибка при выполнении дампа: {}", e.getMessage(), e);
            } finally {
                running.set(false);
            }
        }, "dump-thread").start();
    }

    /** Запрос на остановку. */
    public void stopDump() {
        coordinator.stop();
        log.info("Получен сигнал остановки дампа");
    }

    /** Получить сводку прогресса. */
    public DumpProgress getProgress() {
        DumpProgress dp = new DumpProgress(processed.get(), running.get());
        dp.setSavedMessages(savedMessages.get());
        dp.setSavedPhotos(savedPhotos.get());
        dp.setSavedVideos(savedVideos.get());
        dp.setSavedAudio(savedAudio.get());
        dp.setSavedDocuments(savedDocs.get());
        dp.setSavedLinks(savedLinks.get());

        Map<String, Integer> byExt = new HashMap<>();
        for (Map.Entry<String, AtomicInteger> e : docsByExt.entrySet()) {
            byExt.put(e.getKey(), e.getValue().get());
        }
        dp.setDocumentsByExtension(byExt);
        return dp;
    }

    /** Мягкая остановка с ожиданием. */
    public void stopAndAwait(long timeoutMs) {
        coordinator.stop();
        long until = System.currentTimeMillis() + timeoutMs;
        while (running.get() && System.currentTimeMillis() < until) {
            try { Thread.sleep(100); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }

    public void clearDownloaderCache() {
        coordinator.clearDownloaderCache();
    }
}
