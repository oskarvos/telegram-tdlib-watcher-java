package com.oleg.td.dump.core;

import com.oleg.td.dump.api.DumpProgress;
import com.oleg.td.dump.api.DumpRequest;
import com.oleg.td.integrations.tdlibs.AuthFlow;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.dump.persistence.DumpDbManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сервис-обёртка: старт/стоп дампа и прогресс + счётчики сохранённых сущностей.
 */
@Service
public class DumpService {
    private static final Logger log = LoggerFactory.getLogger(DumpService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicInteger processed     = new AtomicInteger(0);
    private final AtomicInteger savedMessages = new AtomicInteger(0);
    private final AtomicInteger savedPhotos   = new AtomicInteger(0);
    private final AtomicInteger savedVideos   = new AtomicInteger(0);
    private final AtomicInteger savedAudio    = new AtomicInteger(0);
    private final AtomicInteger savedDocs     = new AtomicInteger(0);
    private final AtomicInteger savedLinks    = new AtomicInteger(0);

    private final ConcurrentHashMap<String, AtomicInteger> docsByExt = new ConcurrentHashMap<>();

    private final AuthFlow authFlow;
    private final ChatDumpCoordinator coordinator;
    private final ChatResolver resolver;
    private final DumpDbManager db;

    public DumpService(AuthFlow authFlow, ChatDumpCoordinator coordinator, ChatResolver resolver, DumpDbManager db) {
        this.authFlow = authFlow;
        this.coordinator = coordinator;
        this.resolver = resolver;
        this.db = db;
    }

    public synchronized void startDump(DumpRequest request) {
        if (running.get()) {
            log.warn("Дамп уже выполняется");
            return;
        }
        running.set(true);

        processed.set(0);
        savedMessages.set(0);
        savedPhotos.set(0);
        savedVideos.set(0);
        savedAudio.set(0);
        savedDocs.set(0);
        savedLinks.set(0);
        docsByExt.clear();

        try {
            if (!authFlow.isAuthorized()) {
                log.error("Авторизация не выполнена — дамп прерван");
                return;
            }

            for (String ref : request.getChats()) {
                try {
                    long chatId = resolver.resolveFlexible(ref);
                    db.prepareSchema(chatId);
                } catch (Exception ex) {
                    log.error("Не удалось подготовить схему для '{}': {}", ref, ex.getMessage(), ex);
                }
            }

            log.info("Запуск дампа чатов...");
            coordinator.dumpChats(request, new DumpListener() {
                @Override public void onProgress() { processed.incrementAndGet(); }
                @Override public void onSavedMessage() { savedMessages.incrementAndGet(); }
                @Override public void onSavedPhoto()   { savedPhotos.incrementAndGet(); }
                @Override public void onSavedVideo()   { savedVideos.incrementAndGet(); }
                @Override public void onSavedAudio()   { savedAudio.incrementAndGet(); }
                @Override public void onSavedDocument(String ext) {
                    savedDocs.incrementAndGet();
                    String key = (ext == null || ext.isBlank()) ? "unknown" : ext.toLowerCase();
                    docsByExt.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
                }
                @Override public void onSavedLinks(int count) { savedLinks.addAndGet(Math.max(0, count)); }
            });
            log.info("Дамп завершён");
        } catch (Exception e) {
            log.error("Ошибка при выполнении дампа: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    public void stopDump() {
        coordinator.stop();
        log.info("Получен сигнал остановки дампа");
    }

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
}
