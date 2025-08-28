package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AppInitializer {
    private static final Logger log = LoggerFactory.getLogger(AppInitializer.class);

    private final AuthFlow authFlow;
    private final Config config;

    public AppInitializer(AuthFlow authFlow, Config config) {
        this.authFlow = authFlow;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("=== ИНИЦИАЛИЗАЦИЯ TDLib ===");
        log.info("CONFIG: api_id={}, has_api_hash={}, db_dir={}, files_dir={}, case_insensitive=true",
                config.getTdlib().getApiId(),
                config.getTdlib().getApiHash() != null && !config.getTdlib().getApiHash().isEmpty(),
                config.getTdlib().getDatabaseDirectory(),
                config.getTdlib().getFilesDirectory());

        log.info("API ID: {}", config.getTdlib().getApiId());
        log.info("База данных: {}", config.getTdlib().getDatabaseDirectory());
        log.info("Телефон: {}", config.getAuth().getPhone());
        log.info("Группы: {}", config.getGroups());

        // Настраиваем обработчики авторизации
        authFlow.wireInto();

        // Запускаем авторизацию
        try {
            authFlow.authorizeBlocking();
            log.info("✅ Авторизация успешно завершена!");

        } catch (Exception e) {
            log.error("❌ Ошибка авторизации: {}", e.getMessage());
            log.error("Ошибка авторизации", e);
        }
    }
}