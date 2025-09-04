package com.oleg.td;

import java.util.List;

public class MonitorRequest {
    private List<String> chats;
    private String searchTerm;
    private boolean caseSensitive;

    public MonitorRequest() {
    }

    public MonitorRequest(List<String> chats, String searchTerm, boolean caseSensitive) {
        this.chats = chats;
        this.searchTerm = searchTerm;
        this.caseSensitive = caseSensitive;
    }

    // Геттеры и сеттеры
    public List<String> getChats() {
        return chats;
    }

    public void setChats(List<String> chats) {
        this.chats = chats;
    }

    public String getSearchTerm() {
        return searchTerm;
    }

    public void setSearchTerm(String searchTerm) {
        this.searchTerm = searchTerm;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }
}