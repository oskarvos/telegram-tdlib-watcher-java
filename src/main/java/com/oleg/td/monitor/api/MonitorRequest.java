package com.oleg.td.monitor.api;

import java.util.List;

public class MonitorRequest {
    private List<String> chats;
    private String keyword;
    private boolean caseSensitive;
    private boolean useRegex;

    // НОВОЕ: фиксированные интервалы («1s», «30s», «1m», «5m», «30m», «1h», «1d»)
    private String pollInterval = "1m";

    // BACKWARD-COMPAT: старое поле, если придёт — округлим к ближайшему допустимому
    private Integer pollIntervalMs;

    public List<String> getChats() { return chats; }
    public void setChats(List<String> chats) { this.chats = chats; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public boolean isCaseSensitive() { return caseSensitive; }
    public void setCaseSensitive(boolean caseSensitive) { this.caseSensitive = caseSensitive; }

    public boolean isUseRegex() { return useRegex; }
    public void setUseRegex(boolean useRegex) { this.useRegex = useRegex; }

    public String getPollInterval() { return pollInterval; }
    public void setPollInterval(String pollInterval) { this.pollInterval = pollInterval; }

    public Integer getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(Integer pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }
}
