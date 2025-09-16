package com.oleg.td.search.api;

public class SearchRequest {
    private String chat;  // изменено с List<String> на String
    private String keyword;
    private boolean caseSensitive;
    private boolean useRegex;
    private boolean wholeWord;

    public SearchRequest() {
    }

    public SearchRequest(String chat,
                         String keyword,
                         boolean caseSensitive,
                         boolean useRegex,
                         boolean wholeWord) {
        this.chat = chat;
        this.keyword = keyword;
        this.caseSensitive = caseSensitive;
        this.useRegex = useRegex;
        this.wholeWord = wholeWord;
    }

    public String getChat() {
        return chat;
    }

    public void setChat(String chat) {
        this.chat = chat;
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
