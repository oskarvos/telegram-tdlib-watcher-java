package com.oleg.td;

public class MonitorStatus {
    private boolean monitoring;
    private int monitoredChats;
    private String searchTerm;
    private int matchesFound;

    public MonitorStatus(boolean monitoring, int monitoredChats, String searchTerm, int matchesFound) {
        this.monitoring = monitoring;
        this.monitoredChats = monitoredChats;
        this.searchTerm = searchTerm;
        this.matchesFound = matchesFound;
    }

    // Геттеры и сеттеры
    public boolean isMonitoring() {
        return monitoring;
    }

    public void setMonitoring(boolean monitoring) {
        this.monitoring = monitoring;
    }

    public int getMonitoredChats() {
        return monitoredChats;
    }

    public void setMonitoredChats(int monitoredChats) {
        this.monitoredChats = monitoredChats;
    }

    public String getSearchTerm() {
        return searchTerm;
    }

    public void setSearchTerm(String searchTerm) {
        this.searchTerm = searchTerm;
    }

    public int getMatchesFound() {
        return matchesFound;
    }

    public void setMatchesFound(int matchesFound) {
        this.matchesFound = matchesFound;
    }
}