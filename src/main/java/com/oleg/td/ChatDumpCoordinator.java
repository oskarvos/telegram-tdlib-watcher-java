package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Отвечает за полную выгрузку истории чата.
 */
public class ChatDumpCoordinator {
    private final TdJsonClient client;
    private final MessageLogger logger;

    public ChatDumpCoordinator(TdJsonClient client, MessageLogger logger) {
        this.client = client;
        this.logger = logger;
    }

    /** Вызывается при совпадении паттерна или при первом подключении. */
    public void onPatternMatch(long chatId) {
        dumpWholeChat(chatId);
    }

    /** Полный дамп: от самых новых к самым старым. */
    private void dumpWholeChat(long chatId) {
        long from = 0;          // 0 => начать с последних сообщений
        final int page = 100;   // пачками по 100
        while (true) {
            try {
                ObjectNode req = Utils.obj("getChatHistory");
                req.put("chat_id", chatId);
                req.put("from_message_id", from);
                req.put("offset", 0);
                req.put("limit", page);
                req.put("only_local", false);

                JsonNode resp = client.request(req).get();
                if (!"messages".equals(resp.path("@type").asText())) break;

                JsonNode arr = resp.path("messages");
                if (!arr.isArray() || arr.size() == 0) break;

                // TDLib возвращает от новых к старым
                long oldestId = from;
                for (JsonNode m : arr) {
                    logger.persistMessageNode(m);
                    long mid = m.path("id").asLong(0);
                    if (oldestId == 0 || (mid != 0 && (oldestId == 0 || mid < oldestId))) {
                        oldestId = mid;
                    }
                }

                if (oldestId <= 0 || oldestId == from) break;
                from = oldestId;

                // небольшая пауза, чтобы не душить TDLib
                Thread.sleep(120);
            } catch (Exception e) {
                // в случае сетевых ошибок пробуем аккуратно выйти
                break;
            }
        }
    }
}
