package com.oleg.td;

public final class EnvVars {
    private EnvVars() {
    }

    public static String get(String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            String value = System.getenv(key);
            if (value == null || value.isBlank()) {
                value = System.getProperty(key);
            }
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}