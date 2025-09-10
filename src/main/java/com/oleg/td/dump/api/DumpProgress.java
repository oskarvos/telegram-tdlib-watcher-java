package com.oleg.td.dump.api;

public class DumpProgress {
    private int processed;
    private boolean running;

    public DumpProgress(int processed, boolean running) {
        this.processed = processed;
        this.running = running;
    }

    public int getProcessed() {
        return processed;
    }

    public void setProcessed(int processed) {
        this.processed = processed;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}