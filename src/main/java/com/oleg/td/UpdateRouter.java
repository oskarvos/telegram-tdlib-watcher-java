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
        log.info("Добавлен обработчик обновлений. Всего обработчиков: {}", handlers.size());
    }

    public void remove(Consumer<ObjectNode> handler) {
        handlers.remove(handler);
        log.info("Удален обработчик обновлений. Всего обработчиков: {}", handlers.size());
    }

    public void handleUpdate(ObjectNode update) {
        String updateType = update.path("@type").asText();
        log.debug("Обработка обновления: {}", updateType);

        for (var h : handlers) {
            try {
                h.accept(update);
            } catch (Exception e) {
                log.warn("Ошибка в обработчике обновлений", e);
            }
        }
    }
}