package com.oleg.td;

import java.util.Optional;

/**
 * Загрузка TDLib (JNA) и настройка уровней логирования.
 */
public final class TDLibLoader {
    static {
        System.setProperty("jna.encoding", "UTF-8");
    }

    public static TDLib loadFromEnvOrConfig(Config cfg) {
        String libPath = Optional.ofNullable(System.getenv("TDLIB_PATH"))
                .orElse(Optional.ofNullable(cfg.tdlib.lib_path).orElse("/home/oleg/td/build/libtdjson.so"));
        System.out.println("Loading TDLib from: " + libPath);
        return TDLib.load(libPath);
    }

    public static void configureVerbosity(TDLib lib, int maxLevel) {
        lib.td_set_log_message_callback(maxLevel, (lvl, msg) -> {
            if (lvl <= 1) System.out.printf("TDLib[%d]: %s%n", lvl, msg);
        });
    }
}
