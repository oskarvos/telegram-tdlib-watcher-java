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

/**
 * Service that receives updates from {@link TdJsonClient} and dispatches them
 * to registered handlers.
 */
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

    /**
     * Registers a new handler that will be invoked for every incoming update.
     */
    public void add(Consumer<ObjectNode> handler) {
        handlers.add(handler);
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::loop, "td-update-router");
        thread.setDaemon(true);
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                String raw = client.receive(1.0);
                if (raw == null) continue;

                JsonNode parsed = mapper.readTree(raw);
                if (!(parsed instanceof ObjectNode node)) {
                    log.warn("Unexpected update format: {}", raw);
                    continue;
                }

                for (Consumer<ObjectNode> h : handlers) {
                    try {
                        h.accept(node);
                    } catch (Exception e) {
                        log.warn("Update handler threw exception", e);
                    }
                }
            } catch (IOException e) {
                log.error("Failed to parse update", e);
            } catch (Exception e) {
                log.error("Error in update loop", e);
            }
        }
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}