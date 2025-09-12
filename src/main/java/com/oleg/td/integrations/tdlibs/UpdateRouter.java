package com.oleg.td.integrations.tdlibs;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Простой маршрутизатор обновлений TDLib.
 * Нити-безопасное добавление/удаление обработчиков.
 */
@Component
public class UpdateRouter {

    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);

    private final CopyOnWriteArrayList<Consumer<ObjectNode>> handlers = new CopyOnWriteArrayList<>(); // обработчики

    // регистрирует обработчик
    public void add(Consumer<ObjectNode> handler) { handlers.add(handler); }

    // удаляет обработчик
    public void remove(Consumer<ObjectNode> handler) { handlers.remove(handler); }

    // передаёт обновление всем обработчикам
    public void handleUpdate(ObjectNode update) {
        for (var h : handlers) {
            try { h.accept(update); }
            catch (Exception e) { log.warn("Ошибка обработчика обновлений", e); }
        }
    }
}
