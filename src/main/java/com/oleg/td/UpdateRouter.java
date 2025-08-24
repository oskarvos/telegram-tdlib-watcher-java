package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Простой роутер апдейтов от TdJsonClient к подписчикам.
 */
public class UpdateRouter {
    private final CopyOnWriteArrayList<Consumer<JsonNode>> handlers = new CopyOnWriteArrayList<>();

    public UpdateRouter(List<Consumer<JsonNode>> seed) {
        if (seed != null) handlers.addAll(seed);
    }

    public void add(Consumer<JsonNode> h) {
        handlers.add(h);
    }

    void dispatch(JsonNode n) {
        for (var h : handlers) {
            try {
                h.accept(n);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
