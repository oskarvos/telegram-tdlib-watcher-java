package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранит и обновляет названия чатов по их id.
 */
public class ChatTitleRegistry {
    private final ConcurrentHashMap<Long, String> titles = new ConcurrentHashMap<>();

    public void onUpdateNewChat(JsonNode n) {
        if (!"updateNewChat".equals(n.path("@type").asText())) return;
        long id = n.path("chat").path("id").asLong();
        String title = n.path("chat").path("title").asText("");
        if (!title.isEmpty()) titles.put(id, title);
    }

    public String titleOf(long chatId) {
        return titles.getOrDefault(chatId, String.valueOf(chatId));
    }
}
