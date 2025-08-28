package com.oleg.td;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TdJsonClient {
    private static final Logger log = LoggerFactory.getLogger(TdJsonClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FLOOD_WAIT = Pattern.compile("(\\d+)");

    public enum Channel { AUTH, MAIN }

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

    private volatile boolean running = true;
    private Thread receiverThread;

    public TdJsonClient(@Lazy UpdateRouter router, Config config) {
        this.router = router;
        this.config = config;

        // Загрузка TDLib
        String libPath = config.getLibPath();
        if (libPath != null && !libPath.isEmpty()) {
            // Фраза как в желаемом примере
            log.info("Loading TDLib from: {}", libPath);
            this.tdLib = Native.load(libPath, TdLib.class);
        } else {
            log.info("Loading TDLib from system library 'tdjson'");
            this.tdLib = Native.load("tdjson", TdLib.class);
        }

        this.client = tdLib.td_json_client_create();
        log.info("Create client {}", System.identityHashCode(this.client));

        // Настроим логирование TDLib
        initTdlibLogging();
    }

    private void initTdlibLogging() {
        // Уровень подробности логов TDLib
        ObjectNode verbosity = MAPPER.createObjectNode();
        verbosity.put("@type", "setLogVerbosityLevel");
        verbosity.put("new_verbosity_level", 3);
        send(verbosity);

        // Лог-стрим в "пустой" (без файлов)
        ObjectNode setLogStream = MAPPER.createObjectNode();
        setLogStream.put("@type", "setLogStream");
        ObjectNode empty = MAPPER.createObjectNode();
        empty.put("@type", "logStreamEmpty");
        setLogStream.set("log_stream", empty);
        send(setLogStream);
    }

    @PostConstruct
    public void startReceiver() {
        receiverThread = new Thread(() -> {
            while (running) {
                try {
                    String update = receive(1.0);
                    if (update != null && !update.trim().isEmpty()) {
                        ObjectNode updateNode = (ObjectNode) MAPPER.readTree(update);
                        router.handleUpdate(updateNode);
                    }
                } catch (Exception e) {
                    if (running) {
                        log.error("Ошибка при получении обновления TDLib", e);
                    }
                }
            }
        }, "TDLib-Receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
        log.info("Поток получения обновлений TDLib запущен");
    }

    @PreDestroy
    public void stopReceiver() {
        running = false;
        if (receiverThread != null) {
            receiverThread.interrupt();
            try {
                receiverThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        close();
    }

    public void send(String request) {
        tdLib.td_json_client_send(client, request);
    }

    public void send(String request, Channel channel) {
        send(request);
    }

    public void send(ObjectNode req) {
        send(req.toString());
    }

    public void send(ObjectNode req, Channel channel) {
        send(req.toString(), channel);
    }

    public ObjectNode requestWithFloodWaitSyncLimited(ObjectNode req, int limit, Channel channel) {
        int remaining = Math.min(limit, 60);
        String extra = "req-" + extraId.incrementAndGet();
        req.put("@extra", extra);

        BlockingQueue<ObjectNode> queue = new LinkedBlockingQueue<>();
        Consumer<ObjectNode> handler = node -> {
            if (extra.equals(node.path("@extra").asText())) {
                queue.offer(node);
            }
        };
        router.add(handler);
        try {
            while (true) {
                send(req, channel);
                ObjectNode resp;
                try {
                    resp = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return MAPPER.createObjectNode();
                }

                String type = resp.path("@type").asText();
                if ("error".equals(type) && resp.path("code").asInt() == 429) {
                    int waitSec = extractFloodWait(resp.path("message").asText());
                    if (waitSec <= 0) return resp;

                    if (waitSec > remaining) {
                        try { Thread.sleep(remaining * 1000L); } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        remaining = 0;
                        send(req, channel);
                        try { return queue.take(); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return MAPPER.createObjectNode();
                        }
                    }
                    try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    remaining -= waitSec;
                    continue;
                }
                return resp;
            }
        } finally {
            router.remove(handler);
        }
    }

    public static int extractFloodWait(String message) {
        var m = FLOOD_WAIT.matcher(message);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    public String receive(double timeout) {
        return tdLib.td_json_client_receive(client, timeout);
    }

    public void close() {
        if (client != null) {
            tdLib.td_json_client_destroy(client);
            log.info("TDLib клиент закрыт");
        }
    }
}
