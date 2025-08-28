// ============================================================================
// File: src/main/java/com/oleg/td/ChatDumpCoordinator.java
// Назначение: Координатор дампа чатов. Создает схему БД и последовательно
//              выгружает сообщения/фото/видео/ссылки/аудио в соответствующие таблицы.
//              Работает синхронно (в вызывающем потоке).
// ============================================================================
package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Последовательная выгрузка чата в БД: сообщения, фото, видео, ссылки, аудио/голосовые.
 */
@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern URL_RE = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;

    private volatile boolean stopRequested = false;

    /**
     * @param client   TDLib JSON клиент
     * @param resolver Помощник разрешения ссылок/юзернеймов в chat_id
     * @param db       Менеджер БД (SQLite)
     */
    public ChatDumpCoordinator(TdJsonClient client, ChatResolver resolver, DatabaseManager db) {
        this.client = client;
        this.resolver = resolver;
        this.db = db;
    }

    /**
     * Запускает дамп для списка чатов. Для каждого чата создаёт схему таблиц.
     * @param request           параметры дампа (чаты и типы сущностей)
     * @param progressCallback  коллбек прогресса (вызов на каждое обработанное сообщение)
     */
    public void dumpChats(DumpRequest request, Runnable progressCallback) {
        stopRequested = false;
        for (String chatRef : request.getChats()) {
            if (stopRequested) {
                log.info("Дамп прерван пользователем");
                break;
            }
            long chatId = resolver.resolveOrJoin(chatRef);
            db.prepareFullSchema(chatId); // создаём все необходимые таблицы

            long fromMessageId = 0; // 0 — начать с самых новых
            while (!stopRequested) {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getChatHistory");
                req.put("chat_id", chatId);
                req.put("from_message_id", fromMessageId);
                req.put("offset", 0);
                req.put("limit", 100);
                req.put("only_local", false);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);
                if (!"messages".equals(resp.path("@type").asText())) {
                    log.warn("Неожиданный ответ getChatHistory: {}", resp.path("@type").asText());
                    break;
                }
                ArrayNode arr = (ArrayNode) resp.path("messages");
                if (arr == null || arr.size() == 0) break; // достигли начала истории

                // Сообщения приходят от новых к старым. Обрабатываем все.
                for (JsonNode msg : arr) {
                    long messageId = msg.path("id").asLong();
                    String sender = msg.path("sender_id").toString(); // сохраняем как JSON-строку
                    long date = msg.path("date").asLong();

                    // message content routing
                    String ctype = msg.path("content").path("@type").asText();
                    switch (ctype) {
                        case "messageText" -> {
                            String text = msg.path("content").path("text").path("text").asText("");
                            db.saveTextMessage(chatId, messageId, date, sender, text, msg.toString());
                            if (text != null && !text.isBlank()) saveLinksFromText(chatId, messageId, text, msg.toString());
                        }
                        case "messagePhoto" -> {
                            if (request.isPhotos()) savePhoto(chatId, messageId, date, sender, msg);
                            // возможные ссылки в подписи
                            String caption = msg.path("content").path("caption").path("text").asText("");
                            if (request.isLinks() && !caption.isBlank()) saveLinksFromText(chatId, messageId, caption, msg.toString());
                        }
                        case "messageVideo" -> {
                            if (request.isVideos()) saveVideo(chatId, messageId, date, sender, msg);
                            String caption = msg.path("content").path("caption").path("text").asText("");
                            if (request.isLinks() && !caption.isBlank()) saveLinksFromText(chatId, messageId, caption, msg.toString());
                        }
                        case "messageAudio" -> {
                            if (request.isVideos() || request.isPhotos() || request.isMessages() || request.isLinks()) {}
                            // сохраняем аудио в таблицу audio
                            saveAudio(chatId, messageId, date, sender, msg, "audio");
                        }
                        case "messageVoiceNote" -> {
                            saveAudio(chatId, messageId, date, sender, msg, "voice");
                        }
                        default -> {
                            // любые другие сообщения пишем в messages, если включен флаг сообщений
                            if (request.isMessages()) {
                                db.saveTextMessage(chatId, messageId, date, sender, "", msg.toString());
                            }
                        }
                    }

                    if (progressCallback != null) progressCallback.run();
                    if (stopRequested) break;
                }

                // Переходим к более старым сообщениям
                fromMessageId = arr.get(arr.size() - 1).path("id").asLong();
            }
        }
    }

    /** Прервать текущую операцию дампа. */
    public void stop() { stopRequested = true; }

    /** Сохранение ссылок, извлечённых из текста. */
    private void saveLinksFromText(long chatId, long messageId, String text, String rawJson) {
        List<String> urls = new ArrayList<>();
        Matcher m = URL_RE.matcher(text == null ? "" : text);
        while (m.find()) {
            String url = m.group();
            urls.add(url);
        }
        for (String u : urls) db.saveLink(chatId, messageId, u, text, rawJson);
    }

    /** Сохранение фото-сообщения. */
    private void savePhoto(long chatId, long messageId, long date, String sender, JsonNode msg) {
        JsonNode photo = msg.path("content").path("photo");
        String caption = msg.path("content").path("caption").path("text").asText("");
        // Берём самый большой размер
        JsonNode sizes = photo.path("sizes");
        int w = 0, h = 0; String fileId = null, uniqueId = null, remoteId = null;
        if (sizes.isArray() && sizes.size() > 0) {
            JsonNode last = sizes.get(sizes.size() - 1).path("photo");
            fileId   = last.path("id").asText(null);
            uniqueId = last.path("remote").path("unique_id").asText(null);
            remoteId = last.path("remote").path("id").asText(null);
            w = sizes.get(sizes.size() - 1).path("width").asInt(0);
            h = sizes.get(sizes.size() - 1).path("height").asInt(0);
        }
        db.savePhoto(chatId, messageId, date, sender, fileId, uniqueId, remoteId, w, h, caption, msg.toString());
    }

    /** Сохранение видео-сообщения. */
    private void saveVideo(long chatId, long messageId, long date, String sender, JsonNode msg) {
        JsonNode v = msg.path("content").path("video");
        String fileId   = v.path("video").path("id").asText(null);
        String uniqueId = v.path("video").path("remote").path("unique_id").asText(null);
        String remoteId = v.path("video").path("remote").path("id").asText(null);
        int duration    = v.path("duration").asInt(0);
        String fileName = v.path("file_name").asText(null);
        String mime     = v.path("mime_type").asText(null);
        int width       = v.path("width").asInt(0);
        int height      = v.path("height").asInt(0);
        String caption  = msg.path("content").path("caption").path("text").asText("");
        db.saveVideo(chatId, messageId, date, sender, fileId, uniqueId, remoteId, duration, fileName, mime, width, height, caption, msg.toString());
    }

    /** Сохранение аудио/голосового сообщения. */
    private void saveAudio(long chatId, long messageId, long date, String sender, JsonNode msg, String subtype) {
        JsonNode node = "voice".equals(subtype) ? msg.path("content").path("voice_note") : msg.path("content").path("audio");
        String fileId   = node.path("voice").path("id").asText(null);
        if (fileId == null) fileId = node.path("audio").path("id").asText(null);
        String uniqueId = node.path("voice").path("remote").path("unique_id").asText(null);
        if (uniqueId == null) uniqueId = node.path("audio").path("remote").path("unique_id").asText(null);
        String remoteId = node.path("voice").path("remote").path("id").asText(null);
        if (remoteId == null) remoteId = node.path("audio").path("remote").path("id").asText(null);
        int duration = node.path("duration").asInt(0);
        String fileName = node.path("file_name").asText(null);
        String mime = node.path("mime_type").asText(null);
        String caption = msg.path("content").path("caption").path("text").asText("");
        db.saveAudio(chatId, messageId, date, sender, subtype, fileId, uniqueId, remoteId, duration, fileName, mime, caption, msg.toString());
    }
}