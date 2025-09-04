package com.oleg.td;

import java.time.LocalDateTime;

public class MonitorResult {
    private long chatId;
    private String chatTitle;
    private long messageId;
    private LocalDateTime messageDate;
    private String keyword;
    private String messageText;
    private String senderId;
    private String senderName;

    public MonitorResult() {
    }

    public MonitorResult(long chatId, String chatTitle, long messageId, LocalDateTime messageDate,
                         String keyword, String messageText, String senderId, String senderName) {
        this.chatId = chatId;
        this.chatTitle = chatTitle;
        this.messageId = messageId;
        this.messageDate = messageDate;
        this.keyword = keyword;
        this.messageText = messageText;
        this.senderId = senderId;
        this.senderName = senderName;
    }

    // Геттеры и сеттеры
    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public String getChatTitle() {
        return chatTitle;
    }

    public void setChatTitle(String chatTitle) {
        this.chatTitle = chatTitle;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    public LocalDateTime getMessageDate() {
        return messageDate;
    }

    public void setMessageDate(LocalDateTime messageDate) {
        this.messageDate = messageDate;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getMessageText() {
        return messageText;
    }

    public void setMessageText(String messageText) {
        this.messageText = messageText;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }
}