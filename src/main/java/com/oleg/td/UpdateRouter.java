// ============================================================================
// File: src/main/java/com/oleg/td/UpdateRouter.java
// Назначение: Простейший pub/sub для рассылки апдейтов TDLib подписчикам.
// ============================================================================
package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Роутер TDLib-апдейтов: хранит подписчиков и синхронно вызывает их по порядку.
 */
@Component
public class UpdateRouter {
    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);
    private final CopyOnWriteArrayList<Consumer<ObjectNode>> handlers = new CopyOnWriteArrayList<>();

    /** Добавить обработчик. */
    public void add(Consumer<ObjectNode> handler) { handlers.add(handler); }
    /** Удалить обработчик. */
    public void remove(Consumer<ObjectNode> handler) { handlers.remove(handler); }

    /** Разослать апдейт всем обработчикам. */
    public void handleUpdate(ObjectNode update) {
        for (var h : handlers) {
            try { h.accept(update); } catch (Exception e) { log.warn("Ошибка в обработчике апдейта", e); }
        }
    }
}