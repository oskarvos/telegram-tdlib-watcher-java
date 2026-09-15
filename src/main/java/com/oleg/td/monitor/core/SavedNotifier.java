package com.oleg.td.monitor.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.common.Utils;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Отправляет найденные совпадения в «Избранное» (Saved Messages).
 * Текст сообщения + ссылка на оригинал.
 */
@Component
public class SavedNotifier {

    private static final Logger log = LoggerFactory.getLogger(SavedNotifier.class);
    private static final int MAX_BODY = 4000;

    private final TdJsonClient client;
    private volatile Long savedChatId = null; // кэш user_id (= chat_id «Избранного»)

    public SavedNotifier(TdJsonClient client) {
        this.client = client;
    }

    /** Отправить уведомление в Избранное. Безопасно вызывать из любого потока. */
    public void notify(long sourceChatId, long messageId, String sourceChatTitle, String text) {
        try {
            long target = resolveSavedChatId();
            if (target == 0) {
                log.warn("Saved Messages chat_id неизвестен — уведомление пропущено");
                return;
            }

            String body = format(sourceChatTitle, text, buildLink(sourceChatId, messageId));

            ObjectNode req = Utils.obj("sendMessage");
            req.put("chat_id", target);
            ObjectNode imc = req.putObject("input_message_content");
            imc.put("@type", "inputMessageText");
            imc.putObject("text").put("text", body);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
            String type = resp.path("@type").asText();

            if ("message".equals(type)) {
                log.info("✅ Уведомление в Избранное: чат={}, msgId={}", sourceChatId, messageId);
            } else {
                log.warn("sendMessage не удалось: {}", resp.toString());
            }
        } catch (Exception e) {
            log.warn("Ошибка уведомления в Избранное: {}", e.getMessage());
        }
    }

    /** chat_id «Избранного» = наш user_id. Кэшируем после первого getMe. */
    private long resolveSavedChatId() {
        if (savedChatId != null) return savedChatId;

        try {
            ObjectNode resp = client.requestWithFloodWaitSyncLimited(
                    Utils.obj("getMe"), 30, TdJsonClient.Channel.MAIN);

            if ("user".equals(resp.path("@type").asText())) {
                long id = resp.path("id").asLong(0);
                if (id > 0) {
                    savedChatId = id;
                    log.info("Saved Messages chat_id = {}", id);
                    return id;
                }
            }
            log.warn("getMe вернул неожиданный ответ: {}", resp.path("@type").asText());
        } catch (Exception e) {
            log.warn("Ошибка getMe: {}", e.getMessage());
        }
        return 0;
    }

    /** Ссылка только для супергрупп/каналов: chat_id = -100XXXXXXXXXX. */
    private String buildLink(long chatId, long messageId) {
        String s = String.valueOf(chatId);
        if (s.startsWith("-100") && s.length() > 4) {
            return "https://t.me/c/" + s.substring(4) + "/" + messageId;
        }
        return null; // обычные группы — ссылки нет
    }

    /** Формат: заголовок + текст + ссылка. */
    private String format(String title, String text, String link) {
        String t = (title == null || title.isBlank()) ? "чат" : title;
        String body = (text == null || text.isBlank()) ? "(без текста)" : text;
        if (body.length() > MAX_BODY) {
            body = body.substring(0, MAX_BODY) + "…";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🔔 ").append(t).append("\n\n").append(body);
        if (link != null) {
            sb.append("\n\n🔗 ").append(link);
        }
        return sb.toString();
    }
}