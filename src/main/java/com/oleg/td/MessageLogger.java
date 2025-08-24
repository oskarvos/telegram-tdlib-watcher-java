package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Логирует текстовые сообщения в SQLite.
 */
public class MessageLogger {
    private final Database db;
    private final UserDirectory users;
    private final ChatTitleRegistry titles;
    private final AtomicReference<Set<Long>> allowedChats = new AtomicReference<>(null);

    public MessageLogger(Database db, UserDirectory users, ChatTitleRegistry titles) {
        this.db = db;
        this.users = users;
        this.titles = titles;
    }

    public void setAllowedChats(Set<Long> chatIds) {
        this.allowedChats.set(chatIds);
    }

    public void onUpdateNewMessage(JsonNode u) {
        if (!"updateNewMessage".equals(u.path("@type").asText())) return;

        JsonNode m = u.path("message");
        long chatId = m.path("chat_id").asLong();

        // фильтр по разрешённым чатам, чтобы не писать "всё подряд"
        Set<Long> allow = allowedChats.get();
        if (allow != null && !allow.contains(chatId)) return;

        JsonNode c = m.path("content");
        if (!"messageText".equals(c.path("@type").asText())) return;

        String text = c.path("text").path("text").asText("");
        long messageId = m.path("id").asLong();
        long dateUnix = m.path("date").asLong(0); // UNIX seconds
        String chatTitle = titles.titleOf(chatId);

        // Определяем отправителя (только если messageSenderUser)
        JsonNode senderNode = m.path("sender_id");
        String senderType = senderNode.path("@type").asText();
        if ("messageSenderUser".equals(senderType)) {
            long userId = senderNode.path("user_id").asLong(0);
            users.getUser(userId).whenComplete((info, ex) -> {
                if (ex != null || info == null) {
                    // Если не смогли получить профиль — пишем минимум
                    db.insertMessage(chatId, chatTitle, messageId, dateUnix,
                            userId, null, null, null, text);
                } else {
                    db.insertMessage(chatId, chatTitle, messageId, dateUnix,
                            info.userId,
                            info.username,
                            info.phone,        // может быть null — это нормально
                            info.displayName,
                            text);
                }
            });
        } else {
            // Сообщение не от конкретного пользователя (канал/аноним/бот и т.п.)
            db.insertMessage(chatId, chatTitle, messageId, dateUnix,
                    null, null, null, null, text);
        }
    }
}
