package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class UserDirectory {
    public static final class UserInfo {
        public final long userId;
        public final String username;   // @username без '@'
        public final String phone;      // может быть пустым, если не контакт
        public final String displayName;

        public UserInfo(long userId, String username, String phone, String displayName) {
            this.userId = userId;
            this.username = username == null || username.isBlank() ? null : username;
            this.phone = phone == null || phone.isBlank() ? null : phone;
            this.displayName = displayName;
        }
    }

    private final TdJsonClient client;
    private final Map<Long, CompletableFuture<UserInfo>> cache = new ConcurrentHashMap<>();

    public UserDirectory(TdJsonClient client) {
        this.client = client;
    }

    public CompletableFuture<UserInfo> getUser(long userId) {
        return cache.computeIfAbsent(userId, this::fetchUserInternal);
    }

    private CompletableFuture<UserInfo> fetchUserInternal(long userId) {
        ObjectNode req = Utils.obj("getUser");
        req.put("user_id", userId);
        return client.request(req).thenApply(this::parseUser);
    }

    private UserInfo parseUser(JsonNode n) {
        // Ожидаем объект типа "user"
        if (!"user".equals(n.path("@type").asText())) {
            return new UserInfo(0L, null, null, null);
        }
        long id = n.path("id").asLong(0);
        String username = n.path("username").asText(null);
        // ВАЖНО: phone_number часто доступен только для ваших КОНТАКТОВ
        String phone = n.path("phone_number").asText(null);

        String first = n.path("first_name").asText("");
        String last = n.path("last_name").asText("");
        String display = (first + " " + last).trim();
        if (display.isEmpty()) {
            display = n.path("full_name").asText(""); // TDLib иногда заполняет
        }
        if (display.isEmpty() && username != null) {
            display = "@" + username;
        }
        if (display.isEmpty()) display = String.valueOf(id);

        return new UserInfo(id, username, phone, display);
    }
}
