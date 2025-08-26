package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ChatDumpCoordinator {
    private final TdJsonClient client;
    private final MessageLogger logger;

    public ChatDumpCoordinator(TdJsonClient client, MessageLogger logger) {
        this.client = client;
        this.logger = logger;
    }

    /** Синхронный полный дамп. Возвращает true, если дошли до самого дна. */
    public boolean dumpWholeChatSync(long chatId) {
        long from = 0;            // 0 — начать от самых новых
        final int limit = 100;    // максимум у TDLib
        int retries = 0, maxRetries = 5;
        boolean reachedBottom = false;

        while (true) {
            int offset = (from == 0) ? 0 : -1;

            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", from);
            req.put("offset", offset);
            req.put("limit", limit);
            req.put("only_local", false);

            JsonNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.HISTORY);
            if ("error".equals(resp.path("@type").asText())) {
                int code = resp.path("code").asInt();
                String msg  = resp.path("message").asText();
                System.err.printf("HISTORY ERROR chat=%d from=%d code=%d msg=%s%n", chatId, from, code, msg);

                if (retries < maxRetries && (code == 429 || code == 408 || code == 420 || code == 500)) {
                    sleep(1000L * (1L << Math.min(retries, 4))); // 1,2,4,8,16 сек
                    retries++;
                    continue;
                }
                break;
            }

            ArrayNode arr = (ArrayNode) resp.path("messages");
            int n = (arr == null) ? 0 : arr.size();
            if (n == 0) { reachedBottom = true; break; }

            long prevFrom = from;
            long minId = Long.MAX_VALUE;

            for (int i = 0; i < n; i++) {
                JsonNode msg = arr.get(i);
                logger.persistMessageNode(msg);
                long id = msg.path("id").asLong(0);
                if (id > 0 && id < minId) minId = id;
            }

            if (minId == Long.MAX_VALUE) { reachedBottom = (n < limit); break; }

            if (minId == prevFrom) {
                // на всякий случай пропустим текущую страницу целиком
                ObjectNode skip = Utils.obj("getChatHistory");
                skip.put("chat_id", chatId);
                skip.put("from_message_id", prevFrom);
                skip.put("offset", -n); // пропускаем n сообщений
                skip.put("limit", limit);
                skip.put("only_local", false);

                JsonNode r2 = client.requestWithFloodWaitSyncLimited(skip, 60, TdJsonClient.Channel.HISTORY);
                if ("error".equals(r2.path("@type").asText())) break;
                ArrayNode a2 = (ArrayNode) r2.path("messages");
                int n2 = (a2 == null) ? 0 : a2.size();
                if (n2 == 0) { reachedBottom = true; break; }

                minId = Long.MAX_VALUE;
                for (int i = 0; i < n2; i++) {
                    JsonNode msg = a2.get(i);
                    logger.persistMessageNode(msg);
                    long id = msg.path("id").asLong(0);
                    if (id > 0 && id < minId) minId = id;
                }
                if (minId == Long.MAX_VALUE || minId == prevFrom) { reachedBottom = true; break; }
            }

            from = minId;
            retries = 0;
        }

        return reachedBottom;
    }

    public void onPatternMatch(long chatId) {
        // если нужно – можно запустить асинхронно, но тогда не ставь флаг бутстрапа заранее
        dumpWholeChatSync(chatId);
    }

    private static void sleep(long ms){ try{ Thread.sleep(ms);}catch(InterruptedException ignored){} }
}
