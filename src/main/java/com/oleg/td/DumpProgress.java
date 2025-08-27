package com.oleg.td;

public class DumpProgress {
    private int percent;
    private boolean running;

    public DumpProgress(int percent, boolean running) {
        this.percent = percent;
        this.running = running;
    }

    public int getPercent() {
        return percent;
    }

    public void setPercent(int percent) {
        this.percent = percent;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}