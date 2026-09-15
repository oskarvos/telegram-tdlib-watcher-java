package com.oleg.td.monitor.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.common.Utils;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Отправляет уведомления в «Избранное» (Saved Messages).
 * Используется для:
 *  - старта мониторинга (заголовок + список чатов + интервал);
 *  - найденных совпадений (текст сообщения + ссылка).
 */
@Component
public class SavedNotifier {

    private static final Logger log = LoggerFactory.getLogger(SavedNotifier.class);
    private static final int MAX_BODY = 4000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final TdJsonClient client;
    private volatile Long targetChatId = null; // куда шлём (задаётся снаружи)

    public SavedNotifier(TdJsonClient client) {
        this.client = client;
    }

    /** Установить целевой чат для уведомлений (вызывает MonitorCoordinator). */
    public void setTargetChat(long chatId) {
        this.targetChatId = chatId;
        log.info("SavedNotifier: цель уведомлений = chat_id {}", chatId);
    }

    /** Уведомление о старте мониторинга. */
    public void notifyStart(java.util.List<String> chats, String keyword, String interval) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("▶️ Мониторинг запущен\n\n");
            sb.append("🕒 ").append(LocalDateTime.now().format(TIME_FMT)).append("\n");
            sb.append("🔑 Ключ: ").append(keyword == null ? "(без ключа)" : "`" + keyword + "`").append("\n");
            sb.append("⏱ Интервал: ").append(interval == null ? "?" : interval).append("\n");
            sb.append("📋 Чатов: ").append(chats == null ? 0 : chats.size()).append("\n\n");
            if (chats != null && !chats.isEmpty()) {
                for (String c : chats) {
                    sb.append("• ").append(c).append("\n");
                }
            }
            send(sb.toString());
        } catch (Exception e) {
            log.warn("Не удалось отправить уведомление о старте: {}", e.getMessage());
        }
    }

    /** Уведомление о найденном совпадении: заголовок + текст + ссылка. */
    public void notifyMatch(long sourceChatId, long messageId, String sourceChatTitle, String text) {
        try {
            String body = format(sourceChatTitle, text, buildLink(sourceChatId, messageId));
            send(body);
        } catch (Exception e) {
            log.warn("Ошибка уведомления в Избранное: {}", e.getMessage());
        }
    }

    // --- внутреннее ---

    /** Отправить текст в чат «Избранное». */
    private void send(String text) {
        Long target = targetChatId;
        if (target == null || target == 0) {
            log.warn("Целевой чат для уведомлений не задан — пропуск");
            return;
        }
        try {
            ObjectNode req = Utils.obj("sendMessage");
            req.put("chat_id", target);
            ObjectNode imc = req.putObject("input_message_content");
            imc.put("@type", "inputMessageText");
            imc.putObject("text").put("text", text);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 30, TdJsonClient.Channel.MAIN);
            String type = resp.path("@type").asText();

            if ("message".equals(type)) {
                log.info("✅ Уведомление в Избранное отправлено ({} символов)", text.length());
            } else {
                log.warn("sendMessage не удалось: {}", resp.toString());
            }
        } catch (Exception e) {
            log.warn("Ошибка sendMessage: {}", e.getMessage());
        }
    }

    /** Ссылка только для супергрупп/каналов: chat_id = -100XXXXXXXXXX. */
    private String buildLink(long chatId, long messageId) {
        String s = String.valueOf(chatId);
        if (s.startsWith("-100") && s.length() > 4) {
            return "https://t.me/c/" + s.substring(4) + "/" + messageId;
        }
        return null; // обычные группы / личные чаты — ссылки нет
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