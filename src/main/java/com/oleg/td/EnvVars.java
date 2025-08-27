package com.oleg.td;

/** Utility for retrieving configuration values from environment variables or system properties. */
public final class EnvVars {
    private EnvVars() {
        // utility class
    }

    /**
     * Returns the first non-blank value among the provided keys from environment variables or
     * system properties. Keys are checked in order.
     *
     * @param keys environment or system property names to check
     * @return value of the first found key, or {@code null} if none are set
     */
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