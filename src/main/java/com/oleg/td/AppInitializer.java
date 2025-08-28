package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AppInitializer {
    // Чтобы первая строка была как в примере: [main] INFO com.oleg.td.App - ...
    private static final Logger log = LoggerFactory.getLogger("com.oleg.td.App");

    private final AuthFlow authFlow;
    private final Config config;

    public AppInitializer(AuthFlow authFlow, Config config) {
        this.authFlow = authFlow;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Ваша целевая строка CONFIG
        log.info("CONFIG: api_id={}, has_api_hash={}, db_dir={}, files_dir={}, case_insensitive={}",
                config.getTdlib().getApiId(),
                config.getTdlib().getApiHash() != null && !config.getTdlib().getApiHash().isEmpty(),
                config.getTdlib().getDatabaseDirectory(),
                config.getTdlib().getFilesDirectory(),
                config.isCaseInsensitive()
        );

        try {
            // Подключаем обработчики авторизации
            authFlow.wireInto();

            // Запускаем блокирующую авторизацию
            authFlow.authorizeBlocking();
            log.info("✅ Авторизация успешно завершена!");
        } catch (Exception e) {
            log.error("❌ Ошибка авторизации: {}", e.getMessage(), e);
        }
    }
}
