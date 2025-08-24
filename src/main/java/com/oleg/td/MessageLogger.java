package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MessageLogger {
    private final Database db;
    private final UserDirectory users;
    private final ChatTitleRegistry titles;
    private final MediaDownloader media;
    private final AtomicReference<Set<Long>> allowedChats = new AtomicReference<>(null);

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

    /** Публичный метод: можно вызывать как из live‑апдейтов, так и из дампера истории. */
    public void persistMessageNode(JsonNode m) {
        long chatId = m.path("chat_id").asLong();
        Set<Long> allow = allowedChats.get();
        if (allow != null && !allow.contains(chatId)) return;

        long messageId = m.path("id").asLong();
        long dateUnix = m.path("date").asLong(0);
        String chatTitle = titles.titleOf(chatId);

        // Отправитель (синхронно: важнее полнота, чем скорость при дампе)
        Long senderUserId = null; String senderUsername = null; String senderPhone = null; String senderName = null;
        JsonNode senderNode = m.path("sender_id");
        if ("messageSenderUser".equals(senderNode.path("@type").asText())) {
            long uid = senderNode.path("user_id").asLong(0);
            senderUserId = uid;
            try {
                UserDirectory.UserInfo info = users.getUser(uid).get(3, TimeUnit.SECONDS);
                if (info != null) {
                    senderUsername = info.username;
                    senderPhone = info.phone;
                    senderName = info.displayName;
                }
            } catch (Exception ignored) {}
        }

        // Текст (если есть)
        String text = null;
        JsonNode c = m.path("content");
        String ctype = c.path("@type").asText();
        if ("messageText".equals(ctype)) {
            text = c.path("text").path("text").asText("");
        }
        db.insertMessage(chatId, chatTitle, messageId, dateUnix,
                senderUserId, senderUsername, senderPhone, senderName, text);

        // Ссылки из форматированного текста
        if ("messageText".equals(ctype)) {
            extractUrlsFromEntitiesAndSave(chatId, messageId, c.path("text"));
        }

        // Медиа: фото
        if ("messagePhoto".equals(ctype)) {
            JsonNode photo = c.path("photo");
            // берём самый большой size
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

        // Медиа: видео
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

        // Документы (например, видео как документ, pdf и т.п.)
        if ("messageDocument".equals(ctype)) {
            JsonNode d = c.path("document");
            long fileId = d.path("document").path("id").asLong(0);
            String local = (fileId != 0) ? media.downloadFileGetPath(fileId) : null;
            db.insertMedia(chatId, messageId, "document",
                    (fileId == 0 ? null : fileId), local, null, null, null);
        }

        // Если в message присутствует web_page (превью), можно также вытащить url
        JsonNode webPage = c.path("web_page");
        if ("webPage".equals(webPage.path("@type").asText())) {
            String url = webPage.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                db.insertLink(chatId, messageId, url);
            }
        }
    }

    private void extractUrlsFromEntitiesAndSave(long chatId, long messageId, JsonNode formattedText) {
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
                    db.insertLink(chatId, messageId, url);
                }
            } else if ("textEntityTypeTextUrl".equals(tt)) {
                String url = t.path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    db.insertLink(chatId, messageId, url);
                }
            }
        }
    }
}
