package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AppInitializer {
    // Keep logger name for desired prefix
    private static final Logger log = LoggerFactory.getLogger("com.oleg.td.App");
    private final AuthFlow authFlow;
    private final Config config;
    private final ChatMonitor chatMonitor;
    private final DatabaseManager db;

    public AppInitializer(AuthFlow authFlow, Config config, ChatMonitor chatMonitor, DatabaseManager db) {
        this.authFlow = authFlow;
        this.config = config;
        this.chatMonitor = chatMonitor;
        this.db = db;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("CONFIG: api_id={}, has_api_hash={}, db_dir={}, files_dir={}, case_insensitive={}",
                config.getTdlib().getApiId(),
                config.getTdlib().getApiHash() != null && !config.getTdlib().getApiHash().isEmpty(),
                config.getTdlib().getDatabaseDirectory(),
                config.getTdlib().getFilesDirectory(),
                config.isCaseInsensitive()
        );
        try {
            authFlow.wireInto();
            authFlow.authorizeBlocking();

            // Инициализация схемы мониторинга
            db.prepareMonitorSchema();
            db.prepareMonitorStateSchema();

            log.info("✅ Авторизация успешно завершена!");
        } catch (Exception e) {
            log.error("❌ Ошибка авторизации: {}", e.getMessage(), e);
        }
    }
}