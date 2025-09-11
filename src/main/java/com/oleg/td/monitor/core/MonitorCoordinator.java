package com.oleg.td.monitor.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.monitor.api.MonitorRequest;
import com.oleg.td.monitor.persistence.MonitorDbManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

@Component
public class MonitorCoordinator {
    private static final Logger log = LoggerFactory.getLogger(MonitorCoordinator.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final MonitorDbManager db;

    private volatile boolean stopRequested = false;

    public MonitorCoordinator(TdJsonClient client, ChatResolver resolver, MonitorDbManager db) {
        this.client = client;
        this.resolver = resolver;
        this.db = db;
    }

    public void monitor(MonitorRequest request, Runnable progressCb, Runnable foundCb) {
        stopRequested = false;

        // интервал опроса
        long intervalMs = toMillisFixed(request.getPollInterval());
        if (request.getPollInterval() == null && request.getPollIntervalMs() != null) {
            intervalMs = nearestAllowed(request.getPollIntervalMs());
        }

        // чаты
        long[] chatIds = request.getChats().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .mapToLong(resolver::resolveFlexible)
                .toArray();

        // схемы MONITOR и метаданные MONITOR (чекпоинт тоже в MONITOR)
        for (long chatId : chatIds) {
            db.prepareMonitorSchema(chatId);
            db.ensureMonitorMetadata(chatId);
        }

        log.info("Мониторинг запущен по {} чатам, интервал {} мс", chatIds.length, intervalMs);

        while (!stopRequested) {
            for (long chatId : chatIds) {
                if (stopRequested) break;

                // 1) читаем чекпоинт; если его нет — инициализируем на "голову" (самое новое сообщение)
                long lastSeen = 0L;
                try {
                    String v = db.loadMonitorCheckpoint(chatId);
                    if (v != null && !v.isBlank()) lastSeen = Long.parseLong(v.trim());
                } catch (Exception ignore) {}

                if (lastSeen == 0L) {
                    // получить id самого нового сообщения
                    ObjectNode headReq = M.createObjectNode();
                    headReq.put("@type", "getChatHistory");
                    headReq.put("chat_id", chatId);
                    headReq.put("from_message_id", 0);
                    headReq.put("offset", 0);
                    headReq.put("limit", 1);
                    headReq.put("only_local", false);

                    ObjectNode headResp = client.requestWithFloodWaitSyncLimited(headReq, 30, TdJsonClient.Channel.MAIN);
                    if ("messages".equals(headResp.path("@type").asText())) {
                        ArrayNode arr = (ArrayNode) headResp.path("messages");
                        if (arr != null && arr.size() > 0) {
                            long top = arr.get(0).path("id").asLong(0);
                            if (top > 0) {
                                db.saveMonitorCheckpoint(chatId, top);
                                lastSeen = top;
                            }
                        }
                    }
                }
                long maxSeen = lastSeen;
                try {
                    // 2) берём последние N сообщений
                    ObjectNode req = M.createObjectNode();
                    req.put("@type", "getChatHistory");
                    req.put("chat_id", chatId);
                    req.put("from_message_id", 0);
                    req.put("offset", 0);
                    req.put("limit", 100);
                    req.put("only_local", false);

                    ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
                    if (!"messages".equals(resp.path("@type").asText())) continue;

                    ArrayNode messages = (ArrayNode) resp.path("messages");
                    if (messages == null || messages.size() == 0) continue;

                    for (JsonNode msg : messages) {
                        if (stopRequested) break;
                        long mid = msg.path("id").asLong(0);
                        if (mid <= lastSeen) continue; // строго только новые

                        LocalDateTime mdt = LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(msg.path("date").asLong(0)),
                                ZoneId.systemDefault());

                        String senderId = msg.path("sender_id").isMissingNode() ? null : msg.path("sender_id").toString();
                        String senderName = senderId; // при необходимости можно обогащать

                        String text = null;
                        JsonNode content = msg.path("content");
                        if ("messageText".equals(content.path("@type").asText())) {
                            text = content.path("text").path("text").asText(null);
                        }
                        if (progressCb != null) progressCb.run();
                        if (text != null && containsKeyword(text, request.getKeyword(),
                                request.isCaseSensitive(), request.isUseRegex())) {
                            db.saveMonitorHit(chatId, mid, mdt, request.getKeyword(), text, senderId, senderName);
                            if (foundCb != null) foundCb.run();
                        }
                        if (mid > maxSeen) maxSeen = mid;
                    }

                    if (maxSeen > lastSeen) db.saveMonitorCheckpoint(chatId, maxSeen);
                } catch (Exception e) {
                    log.warn("Мониторинг: ошибка чата {}: {}", chatId, e.getMessage());
                }
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.info("Мониторинг остановлен");
    }

    private boolean containsKeyword(String text, String keyword, boolean caseSensitive, boolean useRegex) {
        if (text == null || keyword == null || keyword.isEmpty()) return false;

        if (useRegex) {
            try {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                return Pattern.compile(keyword, flags).matcher(text).find();
            } catch (Exception e) {
                log.warn("Некорректный regex '{}': {}", keyword, e.getMessage());
                return false;
            }
        } else {
            return caseSensitive ? text.contains(keyword) : text.toLowerCase().contains(keyword.toLowerCase());
        }
    }

    private static long toMillisFixed(String v) {
        if (v == null) return 60_000L; // по умолчанию 1 мин
        switch (v) {
            case "1s":
                return 1_000L;
            case "30s":
                return 30_000L;
            case "1m":
                return 60_000L;
            case "5m":
                return 300_000L;
            case "30m":
                return 1_800_000L;
            case "1h":
                return 3_600_000L;
            case "1d":
                return 86_400_000L;
            default:
                return 60_000L;
        }
    }

    private static final long[] ALLOWED = {
            1_000L, 30_000L, 60_000L, 300_000L, 1_800_000L, 3_600_000L, 86_400_000L
    };

    private static long nearestAllowed(long ms) {
        long best = ALLOWED[0], diff = Long.MAX_VALUE;
        for (long a : ALLOWED) {
            long d = Math.abs(a - ms);
            if (d < diff) {
                diff = d;
                best = a;
            }
        }
        return best;
    }

    // MonitorCoordinator.java

    private long readOrInitCheckpoint(long chatId) {
        long lastSeen = 0L;
        try {
            String v = db.loadMonitorCheckpoint(chatId);
            if (v != null && !v.isBlank()) {
                lastSeen = Long.parseLong(v.trim());
            }
        } catch (Exception ignore) {
        }

        // если чекпоинта нет — установить на актуальную «голову» чата
        if (lastSeen == 0L) {
            long top = fetchLatestMessageId(chatId);
            if (top > 0) {
                db.saveMonitorCheckpoint(chatId, top);
                lastSeen = top;
            }
        }
        return lastSeen;
    }

    private long fetchLatestMessageId(long chatId) {
        var req = M.createObjectNode();
        req.put("@type", "getChatHistory");
        req.put("chat_id", chatId);
        req.put("from_message_id", 0);  // от самой новой
        req.put("offset", 0);
        req.put("limit", 1);            // ровно одно — самое свежее
        req.put("only_local", false);

        var resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
        if ("messages".equals(resp.path("@type").asText())) {
            var arr = (ArrayNode) resp.path("messages");
            if (arr != null && arr.size() > 0) {
                return arr.get(0).path("id").asLong(0);
            }
        }
        return 0L;
    }

    public void stop() {
        stopRequested = true;
    }
}
