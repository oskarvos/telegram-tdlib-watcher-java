package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.concurrent.*;

/**
 * Дамп истории чата с корректной пагинацией и диагностикой доступности истории.
 */
public class ChatDumpCoordinator {
    private final TdJsonClient client;
    private final MessageLogger logger;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    // чтобы не спамить диагностикой — проверяем доступность истории для каждого чата один раз
    private final Set<Long> historyChecked = ConcurrentHashMap.newKeySet();

    public ChatDumpCoordinator(TdJsonClient client, MessageLogger logger) {
        this.client = client;
        this.logger = logger;
    }

    /** Асинхронный запуск полного дампа. */
    public void onPatternMatch(long chatId) {
        pool.submit(() -> dumpWholeChatSync(chatId));
    }

    /** Синхронный полный дамп. Возвращает true, если дошли до самого низа истории. */
    public boolean dumpWholeChatSync(long chatId) {
        // --- диагностика доступности истории один раз на чат ---
        checkHistoryAvailabilityOnce(chatId);

        long from = 0;            // 0 — начать от самых новых
        final int limit = 100;    // максимум у TDLib
        int retries = 0;
        final int maxRetries = 5;
        boolean reachedBottom = false;

        while (true) {
            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", from);
            req.put("offset", from == 0 ? 0 : -1);  // исключаем уже обработанное крайнее сообщение
            req.put("limit", limit);
            req.put("only_local", false);

            JsonNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.HISTORY);
            if ("error".equals(resp.path("@type").asText())) {
                int code = resp.path("code").asInt();
                String msg = resp.path("message").asText();
                System.err.printf("HISTORY ERROR chat=%d from=%d code=%d msg=%s%n", chatId, from, code, msg);

                // мягкие сбои: 429/408/420/500 — подретраим с экспоненциальной паузой
                if (retries < maxRetries && (code == 429 || code == 408 || code == 420 || code == 500)) {
                    sleep(1000L * (1L << Math.min(retries, 4))); // 1,2,4,8,16 сек
                    retries++;
                    continue;
                }
                // другие ошибки — прекращаем, считаем дамп незавершённым
                break;
            }

            ArrayNode arr = (ArrayNode) resp.path("messages");
            int n = (arr == null) ? 0 : arr.size();
            if (n == 0) { // пустая страница — дошли до дна
                reachedBottom = true;
                break;
            }

            long oldest = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                JsonNode msg = arr.get(i);
                logger.persistMessageNode(msg);
                long id = msg.path("id").asLong(0);
                if (id > 0 && id < oldest) oldest = id;
            }

            // если пачка меньше лимита и/или «якорь» не двигается — считаем, что дно
            if (n < limit && oldest == Long.MAX_VALUE) {
                reachedBottom = true;
                break;
            }

            // обновляем якорь; подстрахуемся от зацикливания
            if (oldest == Long.MAX_VALUE || oldest == from) {
                oldest = from - 1;
            }
            from = oldest;
            retries = 0; // успешная страница — сбрасываем ретраи
        }

        return reachedBottom;
    }

    /** Аккуратно закрыть пул задач. */
    public void shutdown() {
        pool.shutdown();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
    }

    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    /**
     * Диагностика: для супергрупп узнаём is_all_history_available.
     * Если false — Telegram не показывает историю до момента вступления аккаунта.
     */
    private void checkHistoryAvailabilityOnce(long chatId) {
        if (!historyChecked.add(chatId)) return; // уже проверяли

        try {
            // 1) getChat -> понять тип чата
            ObjectNode getChat = Utils.obj("getChat");
            getChat.put("chat_id", chatId);
            JsonNode chat = client.requestWithFloodWaitSyncLimited(getChat, 30, TdJsonClient.Channel.GENERAL);
            if (!"chat".equals(chat.path("@type").asText())) return;

            JsonNode type = chat.path("type");
            String t = type.path("@type").asText();
            String title = chat.path("title").asText(String.valueOf(chatId));

            if ("chatTypeSupergroup".equals(t)) {
                long sgId = type.path("supergroup_id").asLong();
                boolean isChannel = type.path("is_channel").asBoolean(false);

                // 2) getSupergroupFullInfo -> is_all_history_available
                ObjectNode req = Utils.obj("getSupergroupFullInfo");
                req.put("supergroup_id", sgId);
                JsonNode info = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.GENERAL);

                boolean all = info.path("is_all_history_available").asBoolean(true);
                if (!all) {
                    System.err.printf(
                            "WARNING: chat \"%s\" (%d) is a %s with restricted history for new members. " +
                                    "Telegram will NOT return messages earlier than your join point. Full dump is impossible.%n",
                            title, chatId, isChannel ? "channel" : "supergroup"
                    );
                } else {
                    System.out.printf(
                            "Info: chat \"%s\" (%d) — full history is available; full dump should be possible.%n",
                            title, chatId
                    );
                }
            }
            // Для basic group/privates такой флаг недоступен — ничего не пишем.
        } catch (Exception e) {
            // Диагностика не должна валить дамп
            System.err.printf("History availability check failed for chat %d: %s%n", chatId, e.getMessage());
        }
    }
}
