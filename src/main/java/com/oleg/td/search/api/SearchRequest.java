package com.oleg.td.search.api;

import java.util.List;

public class SearchRequest {
    private List<String> chats;
    private String keyword;
    private boolean caseSensitive;
    private boolean useRegex;
    private boolean wholeWord;

    public SearchRequest() {
    }

    public SearchRequest(List<String> chats,
                         String keyword,
                         boolean caseSensitive,
                         boolean useRegex,
                         boolean wholeWord) {
        this.chats = chats;
        this.keyword = keyword;
        this.caseSensitive = caseSensitive;
        this.useRegex = useRegex;
        this.wholeWord = wholeWord;
    }

    public List<String> getChats() {
        return chats;
    }

    public void setChats(List<String> chats) {
        this.chats = chats;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public void setCaseSensitive(boolean caseSensitive) {
        this.caseSensitive = caseSensitive;
    }

    public boolean isUseRegex() {
        return useRegex;
    }

    public void setUseRegex(boolean useRegex) {
        this.useRegex = useRegex;
    }

    public boolean isWholeWord() {
        return wholeWord;
    }

    public void setWholeWord(boolean wholeWord) {
        this.wholeWord = wholeWord;
    }
}
