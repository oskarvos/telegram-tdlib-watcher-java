package com.oleg.td.dump.api;

import java.util.Map;

public class DumpProgress {
    private int processed;
    private boolean running;

    private int savedMessages;
    private int savedPhotos;
    private int savedVideos;
    private int savedAudio;
    private int savedDocuments;
    private int savedLinks;

    // ext -> count (txt, pdf, docx, …)
    private Map<String, Integer> documentsByExtension;

    public DumpProgress() {}

    public DumpProgress(int processed, boolean running) {
        this.processed = processed;
        this.running = running;
    }

    public int getProcessed() { return processed; }
    public void setProcessed(int processed) { this.processed = processed; }

    public boolean isRunning() { return running; }
    public void setRunning(boolean running) { this.running = running; }

    public int getSavedMessages() { return savedMessages; }
    public void setSavedMessages(int savedMessages) { this.savedMessages = savedMessages; }

    public int getSavedPhotos() { return savedPhotos; }
    public void setSavedPhotos(int savedPhotos) { this.savedPhotos = savedPhotos; }

    public int getSavedVideos() { return savedVideos; }
    public void setSavedVideos(int savedVideos) { this.savedVideos = savedVideos; }

    public int getSavedAudio() { return savedAudio; }
    public void setSavedAudio(int savedAudio) { this.savedAudio = savedAudio; }

    public int getSavedDocuments() { return savedDocuments; }
    public void setSavedDocuments(int savedDocuments) { this.savedDocuments = savedDocuments; }

    public int getSavedLinks() { return savedLinks; }
    public void setSavedLinks(int savedLinks) { this.savedLinks = savedLinks; }

    public Map<String, Integer> getDocumentsByExtension() { return documentsByExtension; }
    public void setDocumentsByExtension(Map<String, Integer> documentsByExtension) { this.documentsByExtension = documentsByExtension; }
}
