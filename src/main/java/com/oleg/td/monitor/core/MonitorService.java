package com.oleg.td.monitor.core;

import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.monitor.api.MonitorProgress;
import com.oleg.td.monitor.api.MonitorRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сервис управления мониторингом.
 * Запускает/останавливает поток и отдаёт прогресс.
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final AtomicBoolean running = new AtomicBoolean(false); // флаг работы
    private final AtomicInteger processed = new AtomicInteger(0);   // счётчик обработанных
    private final AtomicInteger found = new AtomicInteger(0);       // счётчик попаданий

    private final MonitorCoordinator coordinator; // координатор
    private final ChatResolver resolver;          // резолвер чатов

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "monitor-thread");
        t.setDaemon(true);
        return t;
    });

    private Future<?> monitorFuture; // трекинг задачи

    public MonitorService(MonitorCoordinator coordinator, ChatResolver resolver) {
        this.coordinator = coordinator;
        this.resolver = resolver;
    }

    /** Резолв chat_id из строки. */
    public long resolveChatId(String chatRef) {
        return resolver.resolveFlexible(chatRef);
    }

    /** Запуск мониторинга. */
    public synchronized void startMonitoring(MonitorRequest req) {
        if (running.get()) {
            log.warn("Мониторинг уже выполняется");
            return;
        }

        running.set(true);
        processed.set(0);
        found.set(0);

        monitorFuture = executor.submit(() -> {
            try {
                coordinator.monitor(req, this::incProcessed, this::incFound);
            } catch (Exception e) { // защита от сбоев в потоке
                log.error("Критическая ошибка мониторинга: {}", e.getMessage(), e);
            } finally {
                running.set(false);
                monitorFuture = null; // освобождаем ссылку
            }
        });

        log.info("Задача мониторинга отправлена в исполнение");
    }

    /** Остановка мониторинга. */
    public void stopMonitoring() {
        coordinator.stop(); // отдаём флаг
        Future<?> f = monitorFuture;
        if (f != null && !f.isDone()) {
            f.cancel(true); // прервём sleep()
        }
        log.info("Получен сигнал остановки мониторинга");
    }

    /** Остановка и ожидание завершения потока. */
    public void stopMonitoringAndWait(long timeoutMs) {
        stopMonitoring();
        Future<?> f = monitorFuture;
        if (f != null) {
            try {
                f.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("Ожидание завершения мониторинга по таймауту {} мс", timeoutMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (CancellationException | ExecutionException e) {
                // уже остановлено/ошибка — просто завершаем
            }
        }
        log.info("Мониторинг остановлен (готово к очистке БД)");
    }

    /** Текущий прогресс. */
    public MonitorProgress getProgress() {
        return new MonitorProgress(processed.get(), found.get(), running.get());
    }

    // инкремент обработанных сообщений
    private void incProcessed() { processed.incrementAndGet(); }

    // инкремент найденных совпадений
    private void incFound() { found.incrementAndGet(); }
}
