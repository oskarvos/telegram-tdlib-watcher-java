package com.oleg.td;

import java.io.PrintStream;

public final class LogBootstrap {
    private static volatile boolean installed = false;

    private LogBootstrap() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;

        PrintStream out = System.out;
        PrintStream err = System.err;

        System.setOut(new PrintStream(new LogTeeOutputStream(out), true));
        System.setErr(new PrintStream(new LogTeeOutputStream(err), true));

        System.out.println("Log console attached.");
    }
}
