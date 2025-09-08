package com.oleg.td.monitor.api;

public class MonitorProgress {
    private int processedMessages;
    private int foundMessages;
    private boolean running;

    public MonitorProgress() {
    }

    public MonitorProgress(int processed, int found, boolean running) {
        this.processedMessages = processed;
        this.foundMessages = found;
        this.running = running;
    }

    public int getProcessedMessages() {
        return processedMessages;
    }

    public void setProcessedMessages(int processedMessages) {
        this.processedMessages = processedMessages;
    }

    public int getFoundMessages() {
        return foundMessages;
    }

    public void setFoundMessages(int foundMessages) {
        this.foundMessages = foundMessages;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}
