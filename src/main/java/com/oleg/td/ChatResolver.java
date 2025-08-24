package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Разрешает ссылки t.me в chat_id и при необходимости вступает в чат.
 */
public class ChatResolver {
    private final TdJsonClient client;

    public ChatResolver(TdJsonClient client) {
        this.client = client;
    }

    public Set<Long> resolveGroups(List<String> links) {
        Set<Long> ids = new HashSet<>();
        for (String link : links) {
            String L = link == null ? "" : link.trim();
            if (L.isEmpty()) continue;
            try {
                if (L.matches("^https?://t.me/(joinchat/).+")) {
                    ObjectNode r = Utils.obj("joinChatByInviteLink");
                    r.put("invite_link", L);
                    JsonNode resp = client.request(r).get();
                    long id = resp.path("chat_id").asLong(0);
                    if (id != 0) ids.add(id);
                } else if (L.matches("^https?://t.me/[^/]+$")) {
                    String username = L.substring(L.lastIndexOf('/') + 1);
                    ObjectNode r = Utils.obj("searchPublicChat");
                    r.put("username", username);
                    JsonNode chat = client.request(r).get();
                    long id = chat.path("id").asLong(0);
                    if (id == 0) {
                        System.err.printf("Public chat not found for %s.%n", username);
                        continue;
                    }
                    ids.add(id);
                    ObjectNode join = Utils.obj("joinChat");
                    join.put("chat_id", id);
                    client.request(join).exceptionally(ex -> null);
                } else {
                    System.err.printf("Unsupported group link format: %s%n", link);
                }
            } catch (Exception e) {
                System.err.println("Group resolve error: " + e.getMessage());
            }
        }
        return ids;
    }
}
