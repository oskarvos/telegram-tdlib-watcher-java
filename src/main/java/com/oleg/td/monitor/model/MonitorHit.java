package com.oleg.td.monitor.model;

import java.time.LocalDateTime;

/**
 * Модель «попадания» мониторинга.
 * Хранится в SQLite БД по каждому чату.
 */
public class MonitorHit {
    private long id;                 // rowid (локальный идентификатор)
    private long chatId;             // chat_id
    private String chatTitle;        // название чата (для отображения)
    private long messageId;          // id сообщения
    private LocalDateTime messageDate; // дата сообщения
    private String keyword;          // искомое слово/шаблон
    private String messageText;      // текст сообщения
    private String senderId;         // TDLib sender_id JSON
    private String senderName;       // человекочитаемое имя (если заполняется)
    private LocalDateTime foundDate; // когда зафиксировано попадание

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getChatId() { return chatId; }
    public void setChatId(long chatId) { this.chatId = chatId; }

    public String getChatTitle() { return chatTitle; }
    public void setChatTitle(String chatTitle) { this.chatTitle = chatTitle; }

    public long getMessageId() { return messageId; }
    public void setMessageId(long messageId) { this.messageId = messageId; }

    public LocalDateTime getMessageDate() { return messageDate; }
    public void setMessageDate(LocalDateTime messageDate) { this.messageDate = messageDate; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public String getMessageText() { return messageText; }
    public void setMessageText(String messageText) { this.messageText = messageText; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public LocalDateTime getFoundDate() { return foundDate; }
    public void setFoundDate(LocalDateTime foundDate) { this.foundDate = foundDate; }
}
