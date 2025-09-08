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

    public MonitorService(MonitorCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public synchronized void startMonitoring(MonitorRequest req) {
        if (running.get()) {
            log.warn("Мониторинг уже выполняется");
            return;
        }
        running.set(true);
        processed.set(0);
        found.set(0);

        new Thread(() -> {
            try {
                coordinator.monitor(req, this::incProcessed, this::incFound);
            } catch (Exception e) {
                log.error("Ошибка мониторинга: {}", e.getMessage(), e);
            } finally {
                running.set(false);
            }
        }, "monitor-thread").start();
    }

    public void stopMonitoring() {
        coordinator.stop();
        log.info("Получен сигнал остановки мониторинга");
    }

    public MonitorProgress getProgress() {
        return new MonitorProgress(processed.get(), found.get(), running.get());
    }

    private void incProcessed(){ processed.incrementAndGet(); }
    private void incFound(){ found.incrementAndGet(); }
}
