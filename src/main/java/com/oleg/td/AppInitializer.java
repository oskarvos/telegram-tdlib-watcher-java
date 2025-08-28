// ============================================================================
// File: src/main/java/com/oleg/td/AppInitializer.java
// Назначение: Точка входа после старта Spring. В одном потоке настраивает
//              обработчики TDLib, запускает блокирующую авторизацию.
//              Все логи — на русском.
// ============================================================================
package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Инициализатор приложения: выводит конфигурацию и запускает блокирующую авторизацию.
 */
@Component
public class AppInitializer {
    /**
     * Логгер с фиксированным именем для читаемости в консоли.
     */
    private static final Logger log = LoggerFactory.getLogger("com.oleg.td.App");

    private final AuthFlow authFlow;
    private final Config config;

    /**
     * @param authFlow Оркестратор авторизации TDLib (машина состояний)
     * @param config   Конфигурация приложения (tdlib, auth и пр.)
     */
    public AppInitializer(AuthFlow authFlow, Config config) {
        this.authFlow = authFlow;
        this.config = config;
    }

    /**
     * Обработчик события готовности Spring-приложения.
     * Выполняется в основном потоке.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("КОНФИГ: api_id={}, api_hash_задан={}, директория_БД={}, директория_файлов={}, регистр_логов_игнорируется={}",
                config.getTdlib().getApiId(),
                config.getTdlib().getApiHash() != null && !config.getTdlib().getApiHash().isEmpty(),
                config.getTdlib().getDatabaseDirectory(),
                config.getTdlib().getFilesDirectory(),
                config.isCaseInsensitive()
        );

        try {
            // Подписываемся на апдейты авторизации
            authFlow.wireInto();
            // Запускаем блокирующую авторизацию (в этом же потоке)
            authFlow.authorizeBlocking();
            log.info("✅ Авторизация успешно завершена");
        } catch (Exception e) {
            log.error("❌ Ошибка авторизации: {}", e.getMessage(), e);
        }
    }
}