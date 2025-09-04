package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class TdlibUpdatePump {
    private static final Logger log = LoggerFactory.getLogger(TdlibUpdatePump.class);

    private final TdJsonClient client;
    private volatile boolean running = false;
    private Thread pumpThread;

    public TdlibUpdatePump(TdJsonClient client) {
        this.client = client;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startPumping() {
        if (running) return;

        running = true;
        log.info("Запуск фонового потока для получения обновлений TDLib...");

        pumpThread = new Thread(() -> {
            while (running) {
                try {
                    client.pumpOnce(0.1); // Короткий таймаут
                    Thread.sleep(50); // Небольшая пауза между опросами
                } catch (Exception e) {
                    log.error("Ошибка в фоновом потоке получения обновлений", e);
                    try {
                        Thread.sleep(1000); // Пауза при ошибке
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "TDLib-Pump-Thread");

        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    @EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void stopPumping() {
        running = false;
        if (pumpThread != null) {
            try {
                pumpThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Фоновый поток получения обновлений остановлен");
    }
}