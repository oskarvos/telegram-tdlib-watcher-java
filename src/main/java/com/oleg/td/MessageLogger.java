package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageLogger {
    private final Database db;
    private final UserDirectory users;
    private final ChatTitleRegistry titles;
    private final MediaDownloader media;
    private final AtomicReference<Set<Long>> allowedChats = new AtomicReference<>(null);

    // Простой fallback-регекс для URL (на случай, если нет entities)
    private static final Pattern URL_RE = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    public MessageLogger(Database db, UserDirectory users, ChatTitleRegistry titles, MediaDownloader media) {
        this.db = db;
        this.users = users;
        this.titles = titles;
        this.media = media;
    }

    public void setAllowedChats(Set<Long> chatIds) { this.allowedChats.set(chatIds); }

    public void onUpdateNewMessage(JsonNode u) {
        if (!"updateNewMessage".equals(u.path("@type").asText())) return;
        persistMessageNode(u.path("message"));
    }

    /** Можно вызывать и из дампа истории. */
    public void persistMessageNode(JsonNode m) {
        long chatId = m.path("chat_id").asLong();
        Set<Long> allow = allowedChats.get();
        if (allow != null && !allow.contains(chatId)) return;

        long messageId = m.path("id").asLong();
        long dateUnix = m.path("date").asLong(0);
        String chatTitle = titles.titleOf(chatId);

        // --- Отправитель ---
        Long senderUserId = null; String senderUsername = null; String senderPhone = null; String senderName = null;
        JsonNode senderNode = m.path("sender_id");
        if ("messageSenderUser".equals(senderNode.path("@type").asText())) {
            long uid = senderNode.path("user_id").asLong(0);
            senderUserId = uid;
            try {
                UserDirectory.UserInfo info = users.getUser(uid).get(3, TimeUnit.SECONDS);
                if (info != null) {
                    senderUsername = info.username;
                    senderPhone = info.phone;      // часто null, если не контакт
                    senderName = info.displayName;
                }
            } catch (Exception ignored) {}
        }

        JsonNode c = m.path("content");
        String ctype = c.path("@type").asText();

        // --- Текст сообщения (messageText) ---
        String mainText = null;
        if ("messageText".equals(ctype)) {
            mainText = c.path("text").path("text").asText("");
        }

        // Сохраняем базовую запись о сообщении сразу (текст или null)
        db.insertMessage(chatId, chatTitle, messageId, dateUnix,
                senderUserId, senderUsername, senderPhone, senderName, mainText);

        // --- Ссылки из messageText.entities + fallback-регекс ---
        if ("messageText".equals(ctype)) {
            extractUrlsFromFormattedTextAndSave(chatId, messageId, c.path("text"));
            if ((mainText == null || mainText.isBlank()) == false) {
                extractUrlsFallback(chatId, messageId, mainText);
            }
        }

        // --- Обработка caption у медиа (ссылки в подписи) ---
        if (!"messageText".equals(ctype)) {
            JsonNode caption = c.path("caption"); // formattedText
            if (caption != null && caption.isObject()) {
                extractUrlsFromFormattedTextAndSave(chatId, messageId, caption);
                String capText = caption.path("text").asText("");
                if (!capText.isBlank()) extractUrlsFallback(chatId, messageId, capText);
            }
        }

        // --- ФОТО ---
        if ("messagePhoto".equals(ctype)) {
            JsonNode photo = c.path("photo");
            JsonNode sizes = photo.path("sizes");
            JsonNode best = null;
            int bestArea = -1;
            for (JsonNode s : sizes) {
                int w = s.path("width").asInt(0);
                int h = s.path("height").asInt(0);
                int area = w * h;
                if (area > bestArea) { bestArea = area; best = s; }
            }
            if (best != null) {
                long fileId = best.path("photo").path("id").asLong(0);
                String local = (fileId != 0) ? media.downloadFileGetPath(fileId) : null;
                Integer w = best.path("width").isInt() ? best.path("width").asInt() : null;
                Integer h = best.path("height").isInt() ? best.path("height").asInt() : null;
                db.insertMedia(chatId, messageId, "photo",
                        (fileId == 0 ? null : fileId), local, w, h, null);
            }
        }

        // --- ВИДЕО ---
        if ("messageVideo".equals(ctype)) {
            JsonNode v = c.path("video");
            long fileId = v.path("video").path("id").asLong(0);
            Integer w = v.path("width").isInt() ? v.path("width").asInt() : null;
            Integer h = v.path("height").isInt() ? v.path("height").asInt() : null;
            Integer dur = v.path("duration").isInt() ? v.path("duration").asInt() : null;
            String local = (fileId != 0) ? media.downloadFileGetPath(fileId) : null;
            db.insertMedia(chatId, messageId, "video",
                    (fileId == 0 ? null : fileId), local, w, h, dur);
        }

        // --- ДОКУМЕНТЫ (в т.ч. видео как документ) ---
        if ("messageDocument".equals(ctype)) {
            JsonNode d = c.path("document");
            long fileId = d.path("document").path("id").asLong(0);
            String local = (fileId != 0) ? media.downloadFileGetPath(fileId) : null;
            db.insertMedia(chatId, messageId, "document",
                    (fileId == 0 ? null : fileId), local, null, null, null);
        }

        // --- GIF/анимации ---
        if ("messageAnimation".equals(ctype)) {
            JsonNode a = c.path("animation");
            long fileId = a.path("animation").path("id").asLong(0);
            Integer w = a.path("width").isInt() ? a.path("width").asInt() : null;
            Integer h = a.path("height").isInt() ? a.path("height").asInt() : null;
            Integer dur = a.path("duration").isInt() ? a.path("duration").asInt() : null;
            String local = (fileId != 0) ? media.downloadFileGetPath(fileId) : null;
            db.insertMedia(chatId, messageId, "animation",
                    (fileId == 0 ? null : fileId), local, w, h, dur);
        }

        // --- Видеозаметки (кружочки) ---
        if ("messageVideoNote".equals(ctype)) {
            JsonNode vn = c.path("video_note");
            long fileId = vn.path("video").path("id").asLong(0);
            Integer dur = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;
            String local = (fileId != 0) ? media.downloadFileGetPath(fileId) : null;
            db.insertMedia(chatId, messageId, "video_note",
                    (fileId == 0 ? null : fileId), local, null, null, dur);
        }
    }

    private void extractUrlsFromFormattedTextAndSave(long chatId, long messageId, JsonNode formattedText) {
        if (formattedText == null || !formattedText.isObject()) return;
        String fullText = formattedText.path("text").asText("");
        JsonNode entities = formattedText.path("entities");
        if (!entities.isArray()) return;

        for (JsonNode e : entities) {
            JsonNode t = e.path("type");
            String tt = t.path("@type").asText();
            if ("textEntityTypeUrl".equals(tt)) {
                int offset = e.path("offset").asInt(0);
                int length = e.path("length").asInt(0);
                if (offset >= 0 && length > 0 && offset + length <= fullText.length()) {
                    String url = fullText.substring(offset, offset + length);
                    if (!url.isBlank()) db.insertLink(chatId, messageId, url);
                }
            } else if ("textEntityTypeTextUrl".equals(tt)) {
                String url = t.path("url").asText(null);
                if (url != null && !url.isBlank()) db.insertLink(chatId, messageId, url);
            }
        }
    }

    private void extractUrlsFallback(long chatId, long messageId, String text) {
        Matcher m = URL_RE.matcher(text);
        while (m.find()) {
            String url = m.group(1);
            if (url != null && !url.isBlank()) db.insertLink(chatId, messageId, url);
        }
    }
}
