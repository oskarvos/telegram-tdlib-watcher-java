package com.oleg.td.integrations.tdlibs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.app.Config;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Component
public class TdJsonClient {
    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Object ioLock = new Object();

    public enum Channel {AUTH, MAIN}

    private interface TdLib extends Library {
        Pointer td_json_client_create();

        void td_json_client_send(Pointer client, String request);

        String td_json_client_receive(Pointer client, double timeout);

        void td_json_client_destroy(Pointer client);
    }

    private final UpdateRouter router;
    private final Config config;
    private final AtomicLong extraId = new AtomicLong();
    private final Pointer client;
    private final TdLib tdLib;

    public TdJsonClient(@Lazy UpdateRouter router, Config config) {
        this.router = router;
        this.config = config;

        String libPath = config.getLibPath();
        if (libPath != null && !libPath.isEmpty()) {
            log.info("Loading TDLib from: {}", libPath);
            this.tdLib = Native.load(libPath, TdLib.class);
        } else {
            log.info("Loading TDLib from system library 'tdjson'");
            this.tdLib = Native.load("tdjson", TdLib.class);
        }
        this.client = tdLib.td_json_client_create();
        log.info("Create client {}", System.identityHashCode(this.client));
        initTdlibLogging();
        log.info("Поток получения обновлений TDLib запущен (single-threaded pump)");
    }

    private void initTdlibLogging() {
        ObjectNode verbosity = MAPPER.createObjectNode();
        verbosity.put("@type", "setLogVerbosityLevel");
        verbosity.put("new_verbosity_level", 3);
        send(verbosity);

        ObjectNode setLogStream = MAPPER.createObjectNode();
        setLogStream.put("@type", "setLogStream");
        ObjectNode empty = MAPPER.createObjectNode();
        empty.put("@type", "logStreamEmpty");
        setLogStream.set("log_stream", empty);
        send(setLogStream);
    }


    public void send(String request) {
        synchronized (ioLock) {
            tdLib.td_json_client_send(client, request);
        }
    }
    public void send(String request, Channel channel) { send(request); }
    public void send(ObjectNode req) { send(req.toString()); }
    public void send(ObjectNode req, Channel channel) { send(req.toString(), channel); }


    /**
     * Single-threaded pump: receive once and dispatch updates (without @extra).
     */
    public void pumpOnce(double timeoutSeconds) {
        String raw;
        synchronized (ioLock) {
            raw = tdLib.td_json_client_receive(client, timeoutSeconds);
        }
        if (raw == null || raw.isBlank()) return;
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(raw);
            if (node.has("@extra")) return;
            router.handleUpdate(node);
        } catch (Exception e) { log.error("Ошибка при обработке апдейта TDLib", e); }
    }

    /**
     * Waits for reply to req (by @extra). Routes other updates synchronously.
     */
    public ObjectNode requestWithFloodWaitSyncLimited(ObjectNode req, int limitSeconds, Channel channel) {
        int remaining = Math.min(Math.max(limitSeconds, 0), 60);
        String extra = "req-" + extraId.incrementAndGet();
        req.put("@extra", extra);

        while (true) {
            send(req, channel);
            long start = System.currentTimeMillis();
            while (true) {
                String raw;
                synchronized (ioLock) {
                    raw = tdLib.td_json_client_receive(client, 2.0);
                }
                if (raw == null || raw.isBlank()) {
                    if (System.currentTimeMillis() - start > 120_000L) {
                        ObjectNode timeout = MAPPER.createObjectNode();
                        timeout.put("@type", "error");
                        timeout.put("code", 408);
                        timeout.put("message", "Request timeout waiting for @extra=" + extra);
                        return timeout;
                    }
                    continue;
                }
                try {
                    ObjectNode node = (ObjectNode) MAPPER.readTree(raw);
                    if (extra.equals(node.path("@extra").asText(null))) {
                        if ("error".equals(node.path("@type").asText()) && node.path("code").asInt() == 429) {
                            int waitSec = extractFloodWait(node.path("message").asText());
                            if (waitSec <= 0) return node;
                            if (waitSec > remaining) {
                                sleep(remaining * 1000L);
                                remaining = 0;
                                break;
                            }
                            sleep(waitSec * 1000L);
                            remaining -= waitSec;
                            break;
                        }
                        return node;
                    }
                    if (!node.has("@extra")) router.handleUpdate(node);
                } catch (Exception e) { log.error("Ошибка парсинга TDLib JSON", e); }
            }
        }
    }

    public static int extractFloodWait(String message) {
        // simple greedy parse of integer seconds anywhere in message
        var m = Pattern.compile("(\\d+)").matcher(message == null ? "" : message);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    @jakarta.annotation.PreDestroy
    public void onClose() { close(); }

    public void close() {
        if (client != null) {
            tdLib.td_json_client_destroy(client);
            log.info("TDLib клиент закрыт");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}