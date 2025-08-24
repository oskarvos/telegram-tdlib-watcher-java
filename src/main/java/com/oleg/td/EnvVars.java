package com.oleg.td;

final class EnvVars {
    private EnvVars(){}

    static String get(String... keys) {
        for (String k : keys) {
            String v = System.getProperty(k);
            if (v != null && !v.isBlank()) return v.trim();
            v = System.getenv(k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    static int getInt(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) v = System.getenv(key);
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v.trim()); } catch (Exception e){ return def; }
    }
}
