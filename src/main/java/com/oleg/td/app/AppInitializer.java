package com.oleg.td.app;

import com.oleg.td.app.config.AppProperties;
import com.oleg.td.app.config.TdlibProperties;
import com.oleg.td.auth.service.AuthRuntimeStore;
import com.oleg.td.integrations.tdlibs.AuthFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;


/**
 * // Класс выполняет инициализацию приложения после старта Spring Boot.
 * // Подключает слушатели TDLib и запускает авторизацию (если заданы параметры).
 */
@Component
public class AppInitializer {
    private static final Logger log = LoggerFactory.getLogger("com.oleg.td.App");

    private final AuthFlow authFlow;
    private final TdlibProperties td;
    private final AppProperties app;
    private final AuthRuntimeStore authStore;

    // // Конструктор: внедрение зависимостей AuthFlow и конфигурации.
    public AppInitializer(AuthFlow authFlow, TdlibProperties td, AppProperties app, AuthRuntimeStore authStore) {
        this.authFlow = authFlow;
        this.td = td;
        this.app = app;
        this.authStore = authStore;
    }

    /**
     * // Метод вызывается после полной готовности приложения.
     * // Логирует ключевые настройки и запускает авторизацию при наличии параметров.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("НАСТРОЙКИ TDLib: api_id={}, есть_api_hash={}, db_dir={}, files_dir={}, регистр-независимый-поиск={}",
                td.getApiId(),
                td.getApiHash() != null && !td.getApiHash().isEmpty(),
                td.getDatabaseDirectory(),
                td.getFilesDirectory(),
                app.isCaseInsensitive()
        );
        try {
            authFlow.wireInto(); // слушатели апдейтов подключаем всегда

            boolean missingApi = td.getApiId() <= 0
                    || td.getApiHash() == null
                    || td.getApiHash().isBlank();
            boolean missingPhone = authStore.get().getPhone() == null
                    || authStore.get().getPhone().isBlank();

            if (missingApi || missingPhone) {
                log.info("⏸️ Параметры TDLib не заданы (api_id/api_hash/phone). " +
                        "Авторизацию не запускаю. Откройте страницу / для ввода данных.");
                return;
            }

            authFlow.authorizeBlocking();
            log.info("✅ Авторизация успешно завершена!");
        } catch (Exception e) {
            log.error("❌ Ошибка авторизации: {}", e.getMessage(), e);
        }
    }
}
