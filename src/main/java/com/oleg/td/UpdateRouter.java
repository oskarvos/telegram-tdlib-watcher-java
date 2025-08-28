package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class UpdateRouter {
    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);

    private final List<Consumer<ObjectNode>> handlers = new CopyOnWriteArrayList<>();

    public void add(Consumer<ObjectNode> handler) {
        handlers.add(handler);
        log.debug("Добавлен новый обработчик. Всего обработчиков: {}", handlers.size());
    }

    public void remove(Consumer<ObjectNode> handler) {
        handlers.remove(handler);
        log.debug("Удален обработчик. Осталось обработчиков: {}", handlers.size());
    }

    /** Вызывается TdJsonClient при получении каждого апдейта. */
    public void handleUpdate(ObjectNode node) {
        for (Consumer<ObjectNode> handler : handlers) {
            try {
                handler.accept(node);
            } catch (Exception e) {
                log.warn("Обработчик вызвал исключение", e);
            }
        }
    }
}
