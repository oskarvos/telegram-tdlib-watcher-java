package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class TdJsonClient implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);
    private final TDLib lib;
    private final Pointer client;
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonNode>> handlers = new CopyOnWriteArrayList<>();
    private final Thread recv;
    private volatile boolean running = true;

    public TdJsonClient(TDLib lib) {
        this.lib = lib;
        this.client = lib.td_json_client_create();
        this.recv = new Thread(this::loop, "tdlib-recv");
        this.recv.setDaemon(true);
        this.recv.start();
    }

    /** Позволяет подписать роутер или отдельный обработчик. */
    public void addUpdateHandler(Consumer<JsonNode> h) { handlers.add(h); }

    /** Удобный способ подключить общий роутер. */
    public void attachRouter(UpdateRouter router) { addUpdateHandler(router::dispatch); }

    public CompletableFuture<JsonNode> request(ObjectNode req) {
        String extra = UUID.randomUUID().toString();
        req.put("@extra", extra);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(extra, f);
        lib.td_json_client_send(client, req.toString());
        return f;
    }

    public void send(ObjectNode req) {
        lib.td_json_client_send(client, req.toString());
    }

    private void loop() {
        while (running) {
            String s = lib.td_json_client_receive(client, 1.0);
            if (s == null) continue;
            try {
                JsonNode n = om.readTree(s);
                String extra = n.path("@extra").asText(null);
                if (extra != null && pending.containsKey(extra)) {
                    var fut = pending.remove(extra);
                    if (fut != null) fut.complete(n);
                } else {
                    for (var h : handlers) {
                        try { h.accept(n); } catch (Exception e) { log.warn("handler", e); }
                    }
                }
            } catch (Exception e) {
                log.warn("parse", e);
            }
        }
    }

    public void close() {
        running = false;
        try { recv.join(Duration.ofSeconds(2).toMillis()); } catch (InterruptedException ignored) {}
        lib.td_json_client_destroy(client);
    }

    public JsonNode execute(ObjectNode req) {
        String s = req.toString();
        String resp = lib.td_json_client_execute(client, s);
        if (resp == null) return null;
        try { return om.readTree(resp); } catch (IOException e) { throw new RuntimeException(e); }
    }
}
