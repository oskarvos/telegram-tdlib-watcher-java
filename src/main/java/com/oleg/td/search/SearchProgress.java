package com.oleg.td.search;

public class SearchProgress {
    private int processedMessages;
    private int foundMessages;
    private boolean running;

    public SearchProgress() {
    }

    public SearchProgress(int processedMessages, int foundMessages, boolean running) {
        this.processedMessages = processedMessages;
        this.foundMessages = foundMessages;
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