package com.oleg.td.monitor.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.common.Utils;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Управляет личным каналом для уведомлений:
 *  - при первом запуске создаёт приватный канал с заданным названием
 *  - сохраняет chat_id в файл
 *  - при повторных запусках переиспользует существующий
 *  - если канал удалён пользователем — создаёт заново
 */
@Component
public class AlertChannelService {

    private static final Logger log = LoggerFactory.getLogger(AlertChannelService.class);
    private static final String CHANNEL_TITLE = "TDLib Alerts";
    private static final String CHANNEL_DESCRIPTION = "Уведомления от TDLib Watcher";

    private final TdJsonClient client;
    private final Path configFile = Paths.get("tdlib", "alert-channel.txt");

    private volatile Long cachedChatId = null;

    public AlertChannelService(TdJsonClient client) {
        this.client = client;
    }

    /** Получить chat_id канала: из кэша → из файла → создать новый. */
    public long getOrCreateChannel() {
        if (cachedChatId != null) {
            return cachedChatId;
        }

        // 1) Пытаемся загрузить сохранённый chat_id
        Long saved = loadSavedChatId();
        if (saved != null) {
            if (chatExists(saved)) {
                cachedChatId = saved;
                log.info("Используем существующий канал алертов: chat_id={}", saved);
                return saved;
            }
            log.warn("Сохранённый канал {} не найден — создаём новый", saved);
        }

        // 2) Создаём новый канал
        long newId = createChannel();
        if (newId == 0) {
            log.error("Не удалось создать канал алертов");
            return 0;
        }

        saveChatId(newId);
        cachedChatId = newId;
        log.info("✅ Канал алертов создан: chat_id={}", newId);
        return newId;
    }

    // --- внутреннее ---

    private long createChannel() {
        try {
            ObjectNode req = Utils.obj("createNewSupergroupChat");
            req.put("title", CHANNEL_TITLE);
            req.put("is_forum", false);
            req.put("is_channel", true);      // true = канал, false = супергруппа
            req.put("description", CHANNEL_DESCRIPTION);
            // location можно не указывать — TDLib обычно принимает и без него

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(
                    req, 30, TdJsonClient.Channel.MAIN);

            String type = resp.path("@type").asText();
            if ("chat".equals(type)) {
                long id = resp.path("id").asLong(0);
                if (id != 0) return id;
            }

            log.warn("createNewSupergroupChat вернул: {}", resp.toString());
            return 0;
        } catch (Exception e) {
            log.error("Ошибка создания канала: {}", e.getMessage(), e);
            return 0;
        }
    }

    private boolean chatExists(long chatId) {
        try {
            ObjectNode req = Utils.obj("getChat");
            req.put("chat_id", chatId);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(
                    req, 15, TdJsonClient.Channel.MAIN);

            return "chat".equals(resp.path("@type").asText());
        } catch (Exception e) {
            log.warn("Ошибка проверки канала {}: {}", chatId, e.getMessage());
            return false;
        }
    }

    private Long loadSavedChatId() {
        try {
            if (!Files.exists(configFile)) return null;
            String s = Files.readString(configFile, StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return null;
            return Long.parseLong(s);
        } catch (Exception e) {
            log.warn("Не удалось прочитать {}: {}", configFile, e.getMessage());
            return null;
        }
    }

    private void saveChatId(long chatId) {
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, Long.toString(chatId), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Не удалось сохранить chat_id в {}: {}", configFile, e.getMessage());
        }
    }
}