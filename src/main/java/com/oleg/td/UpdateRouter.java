package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class UpdateRouter {
    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);

    private final CopyOnWriteArrayList<Consumer<ObjectNode>> handlers = new CopyOnWriteArrayList<>();

    public void add(Consumer<ObjectNode> handler) {
        handlers.add(handler);
    }

    public void remove(Consumer<ObjectNode> handler) {
        handlers.remove(handler);
    }

    /** Вызывается TdJsonClient-ом на каждый апдейт TDLib. */
    public void handleUpdate(ObjectNode update) {
        for (var h : handlers) {
            try {
                h.accept(update);
            } catch (Exception e) {
                log.warn("Handler error", e);
            }
        }
    }
}
