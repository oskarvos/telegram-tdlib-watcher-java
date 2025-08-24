package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Координатор однократного дампа истории чата по событию совпадения. */
public class ChatDumpCoordinator {
    private final TdJsonClient client;
    private final MessageLogger logger;         // используем те же правила сохранения
    private final Set<Long> dumpingNow = ConcurrentHashMap.newKeySet();

    public ChatDumpCoordinator(TdJsonClient client, MessageLogger logger) {
        this.client = client;
        this.logger = logger;
    }

    /** Вызывается при совпадении паттерна в чате. */
    public void onPatternMatch(long chatId) {
        // гарантируем запуск только один раз на чат
        if (!dumpingNow.add(chatId)) return;

        Thread t = new Thread(() -> {
            try {
                dumpWholeHistory(chatId);
            } catch (Exception e) {
                System.err.println("Chat dump failed for " + chatId + ": " + e.getMessage());
            } finally {
                dumpingNow.remove(chatId);
            }
        }, "dump-chat-" + chatId);
        t.setDaemon(true);
        t.start();
    }

    /** Итеративно выгружает всю историю чата (насколько TDLib позволяет). */
    private void dumpWholeHistory(long chatId) throws Exception {
        final int PAGE = 100;
        long fromMessageId = 0;  // 0 => последние сообщения
        int offset = 0;

        while (true) {
            ObjectNode req = Utils.obj("getChatHistory");
            req.put("chat_id", chatId);
            req.put("from_message_id", fromMessageId);
            req.put("offset", offset);   // для старых страниц установим отрицательный
            req.put("limit", PAGE);
            req.put("only_local", false);

            JsonNode resp = client.request(req).get();
            if (!"messages".equals(resp.path("@type").asText())) break;

            JsonNode arr = resp.path("messages");
            if (!arr.isArray() || arr.size() == 0) break;

            long minId = Long.MAX_VALUE;
            for (JsonNode m : arr) {
                logger.persistMessageNode(m);
                long mid = m.path("id").asLong();
                if (mid != 0 && mid < minId) minId = mid;
            }

            if (minId == Long.MAX_VALUE) break;

            // двигаемся в прошлое: TDLib ожидает offset < 0, from_message_id = самый старый с предыдущей страницы
            fromMessageId = minId;
            offset = -PAGE;

            // опционально: условие останова, если TDLib возвращает одинаковый набор
            // но обычно при движении назад размер страниц уменьшается и затем пустеет
        }
        System.out.println("Chat dump done for chat_id=" + chatId);
    }
}
