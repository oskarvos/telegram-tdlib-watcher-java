package com.oleg.td.monitor.core;

import com.oleg.td.monitor.api.MonitorProgress;
import com.oleg.td.monitor.api.MonitorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MonitorService {
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger processed = new AtomicInteger(0);
    private final AtomicInteger found = new AtomicInteger(0);

    private final MonitorCoordinator coordinator;
    private final com.oleg.td.integrations.telegram.ChatResolver resolver;
    private volatile Thread monitorThread;

    public MonitorService(MonitorCoordinator coordinator,
                          com.oleg.td.integrations.telegram.ChatResolver resolver) {
        this.coordinator = coordinator;
        this.resolver = resolver;
    }

    public long resolveChatId(String chatRef){
        return resolver.resolveFlexible(chatRef);
    }

    public synchronized void startMonitoring(MonitorRequest req) {
        if (running.get()) {
            log.warn("Мониторинг уже выполняется");
            return;
        }
        running.set(true);
        processed.set(0);
        found.set(0);

        monitorThread = new Thread(() -> {
            try {
                coordinator.monitor(req, this::incProcessed, this::incFound);
            } catch (Exception e) {
                log.error("Ошибка мониторинга: {}", e.getMessage(), e);
            } finally {
                running.set(false);
                monitorThread = null; // ← важно
            }
        }, "monitor-thread");
        monitorThread.start();
    }

    public void stopMonitoring() {
        coordinator.stop();
        Thread t = monitorThread;
        if (t != null) t.interrupt(); // прервём sleep()
        log.info("Получен сигнал остановки мониторинга");
    }

    /** Остановить мониторинг и подождать завершения потока (для безопасной очистки БД). */
    public void stopMonitoringAndWait(long timeoutMs) {
        stopMonitoring();
        Thread t = monitorThread;
        if (t != null) {
            try {
                t.join(Math.max(0, timeoutMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Мониторинг остановлен (готово к очистке БД)");
    }

    public MonitorProgress getProgress() {
        return new MonitorProgress(processed.get(), found.get(), running.get());
    }

    private void incProcessed(){ processed.incrementAndGet(); }
    private void incFound(){ found.incrementAndGet(); }
}
