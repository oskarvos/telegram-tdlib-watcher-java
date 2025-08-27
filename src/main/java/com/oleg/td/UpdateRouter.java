package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class UpdateRouter {
    private static final Logger log = LoggerFactory.getLogger(UpdateRouter.class);

    private final TdJsonClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Consumer<ObjectNode>> handlers = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread thread;

    public UpdateRouter(TdJsonClient client) {
        this.client = client;
    }

    public void add(Consumer<ObjectNode> handler) {
        handlers.add(handler);
        log.debug("Добавлен новый обработчик. Всего обработчиков: {}", handlers.size());
    }

    public void remove(Consumer<ObjectNode> handler) {
        handlers.remove(handler);
        log.debug("Удален обработчик. Осталось обработчиков: {}", handlers.size());
    }

    public void handleUpdate(ObjectNode node) {
        log.debug("Маршрутизация обновления: {}", node.toString());
        for (Consumer<ObjectNode> handler : handlers) {
            try {
                handler.accept(node);
            } catch (Exception e) {
                log.warn("Обработчик вызвал исключение", e);
            }
        }
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::loop, "td-update-router");
        thread.setDaemon(true);
        thread.start();
        log.info("Маршрутизатор обновлений запущен");
    }

    private void loop() {
        log.info("Начало цикла обработки обновлений");
        while (running) {
            try {
                String raw = client.receive(1.0);
                if (raw == null) continue;

                JsonNode parsed = mapper.readTree(raw);
                if (!(parsed instanceof ObjectNode node)) {
                    log.warn("Неожиданный формат обновления: {}", raw);
                    continue;
                }

                log.debug("Получено сырое обновление: {}", node.toString());

                handleUpdate(node);

            } catch (IOException e) {
                log.error("Ошибка парсинга обновления", e);
            } catch (Exception e) {
                log.error("Ошибка в цикле обработки обновлений", e);
            }
        }
        log.info("Цикл обработки обновлений завершен");
    }

    @PreDestroy
    void stop() {
        log.info("Остановка маршрутизатора обновлений");
        running = false;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        log.info("Маршрутизатор обновлений остановлен");
    }
}