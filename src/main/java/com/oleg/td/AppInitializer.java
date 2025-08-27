package com.oleg.td;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class AppInitializer {

    private final AuthFlow authFlow;
    private final Config config;

    public AppInitializer(AuthFlow authFlow, Config config) {
        this.authFlow = authFlow;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println("=== ИНИЦИАЛИЗАЦИЯ TDLib ===");
        System.out.println("API ID: " + config.getTdlib().getApiId());
        System.out.println("База данных: " + config.getTdlib().getDatabaseDirectory());
        System.out.println("Телефон: " + config.getAuth().getPhone());
        System.out.println("Группы: " + config.getGroups());

        // Настраиваем обработчики авторизации
        authFlow.wireInto();

        // Запускаем авторизацию
        try {
            authFlow.authorizeBlocking();
            System.out.println("✅ Авторизация успешно завершена!");

        } catch (Exception e) {
            System.err.println("❌ Ошибка авторизации: " + e.getMessage());
            e.printStackTrace();
        }
    }
}