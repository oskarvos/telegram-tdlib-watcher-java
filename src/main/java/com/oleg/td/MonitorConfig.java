package com.oleg.td;

import java.util.List;

public class MonitorConfig {
    private List<String> monitoredChats;
    private List<String> keywords;
    private boolean caseSensitive;
    private boolean regexMode;
    private int checkIntervalMinutes;

    public MonitorConfig() {
    }

    public MonitorConfig(List<String> monitoredChats, List<String> keywords,
                         boolean caseSensitive, boolean regexMode, int checkIntervalMinutes) {
        this.monitoredChats = monitoredChats;
        this.keywords = keywords;
        this.caseSensitive = caseSensitive;
        this.regexMode = regexMode;
        this.checkIntervalMinutes = checkIntervalMinutes;
    }

    // Геттеры и сеттеры
    public List<String> getMonitoredChats() {
        return monitoredChats;
    }

    public void setMonitoredChats(List<String> monitoredChats) {
        this.monitoredChats = monitoredChats;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public boolean isRegexMode() {
        return regexMode;
    }

    public void setRegexMode(boolean regexMode) {
        this.regexMode = regexMode;
    }

    public int getCheckIntervalMinutes() {
        return checkIntervalMinutes;
    }

    public void setCheckIntervalMinutes(int checkIntervalMinutes) {
        this.checkIntervalMinutes = checkIntervalMinutes;
    }
}