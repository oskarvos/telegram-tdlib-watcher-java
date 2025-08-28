// ============================================================================
// File: src/main/java/com/oleg/td/DumpService.java
// Назначение: Высокоуровневое управление процессом дампа и прогрессом.
// ============================================================================
package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис запуска/остановки дампа. Один активный дамп за раз.
 */
@Service
public class DumpService {
    private static final Logger log = LoggerFactory.getLogger(DumpService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int progress = 0; // условные проценты (эвристика)

    private final AuthFlow authFlow;
    private final ChatDumpCoordinator coordinator;

    /**
     * @param authFlow    Машина состояний авторизации
     * @param coordinator Координатор дампа
     */
    public DumpService(AuthFlow authFlow, ChatDumpCoordinator coordinator) {
        this.authFlow = authFlow;
        this.coordinator = coordinator;
    }

    /**
     * Запускает дамп (если не запущен). Авторизация выполняется перед дампом.
     */
    public synchronized void startDump(DumpRequest request) {
        if (running.get()) { log.warn("Дамп уже запущен"); return; }
        running.set(true);
        progress = 0;
        try {
            authFlow.wireInto();
            authFlow.authorizeBlocking();
            if (!authFlow.isAuthorized()) { log.error("Авторизация не выполнена"); return; }
            coordinator.dumpChats(request, this::incrementProgress);
        } catch (Exception e) {
            log.error("Дамп завершился с ошибкой", e);
        } finally {
            running.set(false);
            progress = 100;
        }
    }

    /** Остановка дампа. */
    public void stopDump() { coordinator.stop(); }

    /** Текущий прогресс. */
    public DumpProgress getProgress() { return new DumpProgress(Math.min(progress, 100), running.get()); }

    /** Эвристическое увеличение процентов (без знания общего числа сообщений). */
    private synchronized void incrementProgress() { if (progress < 99) progress++; }
}
