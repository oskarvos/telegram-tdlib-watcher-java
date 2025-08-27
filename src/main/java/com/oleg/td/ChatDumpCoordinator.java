package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager databaseManager;
    private volatile boolean stopRequested = false;

    public ChatDumpCoordinator(TdJsonClient client, ChatResolver resolver, DatabaseManager databaseManager) {
        this.client = client;
        this.resolver = resolver;
        this.databaseManager = databaseManager;
    }

    public void dumpChats(DumpRequest request) {
        stopRequested = false;
        for (String chat : request.getChats()) {
            if (stopRequested) {
                log.info("Dump cancelled");
                break;
            }
            long chatId = resolver.resolve(chat);
            databaseManager.prepareSchema(chatId);
            try {
                Thread.sleep(1000); // simulate work
            } catch (InterruptedException ignored) {}
        }
    }

    public void stop() {
        stopRequested = true;
    }
}
