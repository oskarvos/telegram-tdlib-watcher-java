package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DumpService {
    private static final Logger log = LoggerFactory.getLogger(DumpService.class);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int progress = 0;

    private final TdJsonClient tdJsonClient;
    private final AuthFlow authFlow;
    private final ChatDumpCoordinator coordinator;

    public DumpService(TdJsonClient tdJsonClient, AuthFlow authFlow, ChatDumpCoordinator coordinator) {
        this.tdJsonClient = tdJsonClient;
        this.authFlow = authFlow;
        this.coordinator = coordinator;
    }

    public synchronized void startDump(DumpRequest request) {
        if (running.get()) {
            log.warn("Dump already running");
            return;
        }
        running.set(true);
        progress = 0;
        new Thread(() -> {
            try {
                authFlow.authorize();
                coordinator.dumpChats(request, this::incrementProgress);
            } catch (Exception e) {
                log.error("Dump failed", e);
            } finally {
                running.set(false);
            }
        }).start();
    }

    public void stopDump() {
        coordinator.stop();
    }

    public DumpProgress getProgress() {
        return new DumpProgress(progress, running.get());
    }

    private synchronized void incrementProgress() {
        progress++;
    }
}