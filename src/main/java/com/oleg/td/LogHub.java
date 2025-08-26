package com.oleg.td;

import io.javalin.http.sse.SseClient;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;

public class LogHub {
    private static final int DEFAULT_CAPACITY = 1000;
    private static final LogHub INSTANCE = new LogHub(DEFAULT_CAPACITY);

    public static LogHub get() { return INSTANCE; }

    private final LinkedBlockingDeque<String> buffer;
    private final CopyOnWriteArrayList<SseClient> clients = new CopyOnWriteArrayList<>();

    private LogHub(int capacity) {
        this.buffer = new LinkedBlockingDeque<>(capacity);
    }

    public void addClient(SseClient c) {
        // Отдаём текущий буфер при подключении
        for (String line : buffer) {
            c.sendEvent("log", line);
        }
        clients.add(c);
        c.onClose(() -> clients.remove(c));
    }

    public void publish(String line) {
        if (line == null) return;
        if (!buffer.offer(line)) { // переполнен — выкидываем самый старый
            buffer.pollFirst();
            buffer.offer(line);
        }
        // Push всем подписчикам
        for (SseClient c : clients) {
            try {
                c.sendEvent("log", line);
            } catch (Exception ignored) {}
        }
    }

    public void clear() {
        buffer.clear();
    }

    public int size() { return buffer.size(); }
}
