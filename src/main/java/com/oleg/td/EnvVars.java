// ============================================================================
// File: src/main/java/com/oleg/td/EnvVars.java
// Назначение: Утилита для чтения переменных окружения/свойств.
// ============================================================================
package com.oleg.td;

/**
 * Вспомогательный класс: ищет значение в ENV или системных свойствах.
 */
public final class EnvVars {
    private EnvVars() { }

    /**
     * Возвращает первое ненулевое/непустое значение из списка ключей.
     */
    public static String get(String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            String value = System.getenv(key);
            if (value == null || value.isBlank()) value = System.getProperty(key);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
