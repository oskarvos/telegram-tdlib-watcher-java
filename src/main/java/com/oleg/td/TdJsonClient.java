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

    /** Каналы трафика — разные лимиты. */
    public enum Channel { GENERAL, AUTH, HISTORY, DOWNLOAD }

    private final TDLib lib;
    private final Pointer client;
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<JsonNode>> handlers = new CopyOnWriteArrayList<>();
    private final Thread recv;
    private volatile boolean running = true;

    /* ===================== Перестраховка от 429 ===================== */

    /** Глобальный «карантин» после 429: до какого времени блокируем исходящие запросы. */
    private volatile long floodGuardUntilMillis = 0L;

    /** Токен-бакеты на каналы (без фанатизма, но безопасно). */
    private final RateLimiter rlGeneral  = new RateLimiter(15.0, 30.0); // 15 rps, burst 30
    private final RateLimiter rlAuth     = new RateLimiter( 1.0,  2.0); // авторизация — аккуратно
    private final RateLimiter rlHistory  = new RateLimiter( 4.0, 8.0); // история чатов
    private final RateLimiter rlDownload = new RateLimiter( 4.0,  8.0); // загрузки

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

    /* ===================== Публичные send/request ===================== */

    public CompletableFuture<JsonNode> request(ObjectNode req) { return request(req, Channel.GENERAL); }

    public void send(ObjectNode req) { send(req, Channel.GENERAL); }

    public CompletableFuture<JsonNode> request(ObjectNode req, Channel ch) {
        guardAndRate(ch);
        String extra = UUID.randomUUID().toString();
        req.put("@extra", extra);
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(extra, f);
        lib.td_json_client_send(client, req.toString());
        return f;
    }

    public void send(ObjectNode req, Channel ch) {
        guardAndRate(ch);
        lib.td_json_client_send(client, req.toString());
    }

    /* ===================== Flood-wait aware запрос ===================== */

    /**
     * Делает запрос и, если TDLib вернёт 429, пережидает НЕ ДОЛЬШЕ maxWaitSeconds,
     * включает глобальный flood-guard на тот же период и возвращает ответ (без бесконечных ретраев).
     */
    public JsonNode requestWithFloodWaitSyncLimited(ObjectNode req, long maxWaitSeconds, Channel ch) {
        guardAndRate(ch);
        JsonNode resp;
        try {
            resp = request(req, ch).get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (!"error".equals(resp.path("@type").asText())) return resp;

        int code = resp.path("code").asInt(0);
        if (code != 429) return resp;

        long retry = parseRetryAfterSeconds(resp.path("message").asText(""));
        if (retry <= 0) retry = 60;
        long clamp = Math.min(retry, maxWaitSeconds);

        // Включаем глобальный карантин (не больше maxWaitSeconds).
        long now = System.currentTimeMillis();
        floodGuardUntilMillis = Math.max(floodGuardUntilMillis, now + clamp * 1000L);

        System.out.printf("FLOOD-WAIT 429: server says %d s, clamped to %d s. Guard until %s%n",
                retry, clamp, new java.util.Date(floodGuardUntilMillis));
        sleep(clamp * 1000L);

        // Однократная повторная попытка после clamp (если очень хочется).
        try {
            return request(req, ch).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Возвращаем исходную ошибку 429 — пусть зовущий код решает, что делать дальше.
            return resp;
        }
    }

    /* ===================== Низкоуровневая петля приёма ===================== */

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

    /* ===================== Вспомогательные ===================== */

    private void guardAndRate(Channel ch) {
        // 1) Глобальный карантин, если недавно пришёл 429
        long now = System.currentTimeMillis();
        long until = floodGuardUntilMillis;
        if (now < until) {
            long sleep = Math.min(until - now, 60_000L); // перестраховка: не спим дольше 60 сек
            if (sleep > 0) sleep(sleep);
        }
        // 2) Токен-бакет на канал
        switch (ch) {
            case AUTH     -> rlAuth.acquire();
            case HISTORY  -> rlHistory.acquire();
            case DOWNLOAD -> rlDownload.acquire();
            default       -> rlGeneral.acquire();
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static long parseRetryAfterSeconds(String message) {
        if (message == null) return -1;
        int i = message.indexOf("retry after");
        if (i < 0) return -1;
        String tail = message.substring(i + "retry after".length()).trim();
        StringBuilder num = new StringBuilder();
        for (int k = 0; k < tail.length(); k++) {
            char ch = tail.charAt(k);
            if (Character.isDigit(ch)) num.append(ch); else break;
        }
        if (num.length() == 0) return -1;
        try { return Long.parseLong(num.toString()); } catch (Exception e) { return -1; }
    }

    /** Простой токен-бакет без внешних зависимостей. */
    private static final class RateLimiter {
        private final double ratePerSec;
        private final double maxTokens;
        private double tokens;
        private long lastRefillNanos;

        RateLimiter(double ratePerSec, double burst) {
            this.ratePerSec = Math.max(0.001, ratePerSec);
            this.maxTokens = Math.max(1.0, burst);
            this.tokens = burst;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized void acquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return;
            }
            // Сколько примерно ждать до появления 1 токена
            double shortage = 1.0 - tokens;
            long nanosToWait = (long) Math.ceil(shortage / ratePerSec * 1_000_000_000L);
            if (nanosToWait <= 0) nanosToWait = 1_000_0; // ~10 µs
            try {
                long ms = Math.min(100L, Math.max(1L, nanosToWait / 1_000_000L));
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {}
            refill();
            if (tokens < 1.0) {
                tokens = 1.0; // страховка
            }
            tokens -= 1.0;
        }

        private void refill() {
            long now = System.nanoTime();
            double add = (now - lastRefillNanos) / 1_000_000_000.0 * ratePerSec;
            if (add > 0) {
                tokens = Math.min(maxTokens, tokens + add);
                lastRefillNanos = now;
            }
        }
    }
}
