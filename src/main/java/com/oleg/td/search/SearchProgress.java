package com.oleg.td.search;

public class SearchProgress {
    private int processedMessages;
    private boolean running;

    public SearchProgress(int processedMessages, boolean running) {
        this.processedMessages = processedMessages;
        this.running = running;
    }

    public int getProcessedMessages() {
        return processedMessages;
    }

    public void setProcessedMessages(int processedMessages) {
        this.processedMessages = processedMessages;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }
}