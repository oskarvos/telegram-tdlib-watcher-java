package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Логгер входящих/исторических сообщений:
 *  - пишет в SQLite БД, РАЗДЕЛЁННЫЕ ПО ЧАТАМ (через DatabaseRouter)
 *  - скачанные медиа переносит в папку чата:
 *      files/<SanitizedChatTitle>/{photos|videos|voice|temp}/...
 */
public class MessageLogger {
    private final DatabaseRouter dbRouter;
    private final UserDirectory users;
    private final ChatTitleRegistry titles;
    private final MediaDownloader media;
    private final Path filesBaseDir;

    private final AtomicReference<Set<Long>> allowedChats = new AtomicReference<>(null);
    private final ConcurrentHashMap<Long, Boolean> captureEnabled = new ConcurrentHashMap<>();

    public MessageLogger(DatabaseRouter dbRouter,
                         UserDirectory users,
                         ChatTitleRegistry titles,
                         MediaDownloader media,
                         Path filesBaseDir) {
        this.dbRouter = dbRouter;
        this.users = users;
        this.titles = titles;
        this.media = media;
        this.filesBaseDir = filesBaseDir;
    }

    /** Разрешить запись для конкретного чата (включает capture). */
    public void enableCapture(long chatId) { captureEnabled.put(chatId, true); }

    /** Ограничить список чатов, для которых будем писать. */
    public void setAllowedChats(Set<Long> chatIds) { this.allowedChats.set(chatIds); }

    /* ----------------------------------- обработка апдейтов ----------------------------------- */

    public void onUpdateNewMessage(JsonNode u) {
        if (!"updateNewMessage".equals(u.path("@type").asText())) return;
        persistMessageNode(u.path("message"));
    }

    /** Можно вызывать и из дампа истории. */
    public void persistMessageNode(JsonNode m) {
        long chatId = m.path("chat_id").asLong();

        // писать только если включили для этого чата
        if (!captureEnabled.getOrDefault(chatId, false)) return;

        Set<Long> allow = allowedChats.get();
        if (allow != null && !allow.isEmpty() && !allow.contains(chatId)) return;

        // --- выбираем БД для конкретного чата ---
        Database db = dbRouter.forChat(chatId);

        long messageId = m.path("id").asLong();
        long dateUnix = m.path("date").asLong(0);
        String chatTitle = titles.titleOf(chatId);

        // отправитель
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

        JsonNode c = m.path("content");
        String ctype = c.path("@type").asText();

        String mainText = null;
        if ("messageText".equals(ctype)) {
            mainText = c.path("text").path("text").asText("");
        }

        db.insertMessage(chatId, chatTitle, messageId, dateUnix,
                senderUserId, senderUsername, senderPhone, senderName, mainText);

        // ссылки
        if ("messageText".equals(ctype)) {
            extractUrlsFromFormattedTextAndSave(db, chatId, messageId, c.path("text"));
            if (mainText != null && !mainText.isBlank()) extractUrlsFallback(db, chatId, messageId, mainText);
        }
        if (!"messageText".equals(ctype)) {
            JsonNode caption = c.path("caption");
            if (caption != null && caption.isObject()) {
                extractUrlsFromFormattedTextAndSave(db, chatId, messageId, caption);
                String capText = caption.path("text").asText("");
                if (!capText.isBlank()) extractUrlsFallback(db, chatId, messageId, capText);
            }
        }
        JsonNode webPage = c.path("web_page");
        if ("webPage".equals(webPage.path("@type").asText())) {
            String url = webPage.path("url").asText(null);
            if (url != null && !url.isBlank()) db.insertLink(chatId, messageId, url);
        }

        // -------- медиа --------

        // фото
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
                JsonNode fileObj = best.path("photo");
                Long fid = media.ensureFileId(fileObj, "fileTypePhoto");
                String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
                Integer w = best.path("width").isInt() ? best.path("width").asInt() : null;
                Integer h = best.path("height").isInt() ? best.path("height").asInt() : null;
                local = moveToPerChatFolder(local, chatTitle, "photo", messageId);
                db.insertMedia(chatId, messageId, "photo", (fid == null ? null : fid), local, w, h, null);
            }
        }

        // видео
        if ("messageVideo".equals(ctype)) {
            JsonNode v = c.path("video");
            JsonNode fileObj = v.path("video");
            Long fid = media.ensureFileId(fileObj, "fileTypeVideo");
            Integer w = v.path("width").isInt() ? v.path("width").asInt() : null;
            Integer h = v.path("height").isInt() ? v.path("height").asInt() : null;
            Integer dur = v.path("duration").isInt() ? v.path("duration").asInt() : null;
            String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
            local = moveToPerChatFolder(local, chatTitle, "video", messageId);
            db.insertMedia(chatId, messageId, "video", (fid == null ? null : fid), local, w, h, dur);
        }

        // gif/анимации
        if ("messageAnimation".equals(ctype)) {
            JsonNode a = c.path("animation");
            JsonNode fileObj = a.path("animation");
            Long fid = media.ensureFileId(fileObj, "fileTypeAnimation");
            Integer w = a.path("width").isInt() ? a.path("width").asInt() : null;
            Integer h = a.path("height").isInt() ? a.path("height").asInt() : null;
            Integer dur = a.path("duration").isInt() ? a.path("duration").asInt() : null;
            String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
            local = moveToPerChatFolder(local, chatTitle, "animation", messageId);
            db.insertMedia(chatId, messageId, "animation", (fid == null ? null : fid), local, w, h, dur);
        }

        // кружочки
        if ("messageVideoNote".equals(ctype)) {
            JsonNode vn = c.path("video_note");
            JsonNode fileObj = vn.path("video");
            Long fid = media.ensureFileId(fileObj, "fileTypeVideoNote");
            Integer dur = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;
            String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
            local = moveToPerChatFolder(local, chatTitle, "video_note", messageId);
            db.insertMedia(chatId, messageId, "video_note", (fid == null ? null : fid), local, null, null, dur);
        }

        // аудио (музыка)
        if ("messageAudio".equals(ctype)) {
            JsonNode a = c.path("audio");
            String mime = a.path("mime_type").asText("");
            String name = a.path("file_name").asText("");
            if (AUD_MIME.contains(mime) || hasExt(name, AUD_EXT)) {
                JsonNode fileObj = a.path("audio");
                Long fid = media.ensureFileId(fileObj, "fileTypeAudio");
                Integer dur = a.path("duration").isInt() ? a.path("duration").asInt() : null;
                String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
                local = moveToPerChatFolder(local, chatTitle, "audio", messageId);
                db.insertMedia(chatId, messageId, "audio", (fid == null ? null : fid), local, null, null, dur);
            }
        }

        // голосовые
        if ("messageVoiceNote".equals(ctype)) {
            JsonNode vn = c.path("voice_note");
            JsonNode fileObj = vn.path("voice");
            Long fid = media.ensureFileId(fileObj, "fileTypeVoiceNote");
            Integer dur = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;
            String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
            local = moveToPerChatFolder(local, chatTitle, "voice_note", messageId);
            db.insertMedia(chatId, messageId, "voice_note", (fid == null ? null : fid), local, null, null, dur);
        }

        // стикеры
        if ("messageSticker".equals(ctype)) {
            JsonNode s = c.path("sticker");
            JsonNode fileObj = s.path("sticker");
            Long fid = media.ensureFileId(fileObj, "fileTypeSticker");
            String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
            local = moveToPerChatFolder(local, chatTitle, "sticker", messageId);
            db.insertMedia(chatId, messageId, "sticker", (fid == null ? null : fid), local, null, null, null);
        }

        // document — переклассификация (фото/видео/аудио/док)
        if ("messageDocument".equals(ctype)) {
            JsonNode d = c.path("document");
            String mime = d.path("mime_type").asText("");
            String name = d.path("file_name").asText("").toLowerCase();
            JsonNode fileObj = d.path("document");

            String kind = null;
            String fileType = "fileTypeDocument";

            if (IMG_MIME.contains(mime) || hasExt(name, IMG_EXT)) {
                kind = "photo"; fileType = "fileTypePhoto";
            } else if (GIF_MIME.contains(mime) || hasExt(name, GIF_EXT)) {
                kind = "animation"; fileType = "fileTypeAnimation";
            } else if (VID_MIME.contains(mime) || hasExt(name, VID_EXT)) {
                kind = "video"; fileType = "fileTypeVideo";
            } else if (AUD_MIME.contains(mime) || hasExt(name, AUD_EXT)) {
                kind = "audio"; fileType = "fileTypeAudio";
            } else if ("application/pdf".equals(mime) || hasExt(name, set("pdf","zip"))) {
                kind = "document"; fileType = "fileTypeDocument";
            } else {
                kind = "document";
            }

            Long fid = media.ensureFileId(fileObj, fileType);
            String local = (fid != null) ? media.downloadFileGetPath(fid) : null;
            local = moveToPerChatFolder(local, chatTitle, kind, messageId);
            db.insertMedia(chatId, messageId, kind, (fid == null ? null : fid), local, null, null, null);
        }
    }

    /* ----------------------------------- URL извлечение ----------------------------------- */

    private static final Pattern URL_RE = Pattern.compile(
            "\\b((?:https?://|http://|www\\.|t\\.me/)[^\\s]+)",
            Pattern.CASE_INSENSITIVE
    );

    private void extractUrlsFromFormattedTextAndSave(Database db, long chatId, long messageId, JsonNode formattedText) {
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

    private void extractUrlsFallback(Database db, long chatId, long messageId, String text) {
        Matcher m = URL_RE.matcher(text);
        while (m.find()) {
            String url = m.group(1);
            if (url != null && !url.isBlank()) db.insertLink(chatId, messageId, url);
        }
    }

    /* ----------------------------------- Медиа вспомогательные ----------------------------------- */

    // MIME/EXT группы
    private static final Set<String> IMG_MIME = set("image/jpeg","image/png","image/webp");
    private static final Set<String> IMG_EXT  = set("jpg","jpeg","png","webp");
    private static final Set<String> GIF_MIME = set("image/gif");
    private static final Set<String> GIF_EXT  = set("gif");
    private static final Set<String> VID_MIME = set("video/mp4","video/quicktime","video/webm","video/x-matroska");
    private static final Set<String> VID_EXT  = set("mp4","mov","webm","mkv");
    private static final Set<String> AUD_MIME = set("audio/mpeg","audio/mp4","audio/aac","audio/flac","audio/ogg","audio/opus","audio/x-m4a");
    private static final Set<String> AUD_EXT  = set("mp3","m4a","aac","flac","ogg","opus");

    private static Set<String> set(String... v){ return new HashSet<>(Arrays.asList(v)); }

    private static boolean hasExt(String fileName, Set<String> allowed) {
        if (fileName == null || fileName.isBlank()) return false;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length()-1) return false;
        String ext = fileName.substring(dot+1).toLowerCase();
        return allowed.contains(ext);
    }

    /** Перенос скачанного файла в папку конкретного чата/типа. Возвращает новый путь (или исходный при ошибке). */
    private String moveToPerChatFolder(String localPath, String chatTitle, String kind, long messageId) {
        if (localPath == null || localPath.isBlank()) return null;
        try {
            Path src = Path.of(localPath);
            String fileName = (src.getFileName() != null) ? src.getFileName().toString() : (messageId + ".bin");
            Path dstDir = resolveMediaDir(filesBaseDir, chatTitle, kind);
            Files.createDirectories(dstDir);
            Path dst = dstDir.resolve(fileName);
            if (Files.exists(dst)) {
                int dot = fileName.lastIndexOf('.');
                String name = dot > 0 ? fileName.substring(0, dot) : fileName;
                String ext  = dot > 0 ? fileName.substring(dot) : "";
                dst = dstDir.resolve(name + "_" + messageId + ext);
            }
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return dst.toString();
        } catch (Exception e) {
            return localPath; // если не удалось перенести — не ломаем сохранение
        }
    }

    /** Папка медиа для чата/типа: <base>/<SanitizedTitle>/{photos|videos|voice|temp} */
    private static Path resolveMediaDir(Path filesBaseDir, String chatTitle, String kind) {
        String folder = switch (kind) {
            case "photo" -> "photos";
            case "video", "animation", "video_note" -> "videos";
            case "voice_note", "audio" -> "voice";
            default -> "temp";
        };
        return filesBaseDir.resolve(sanitize(chatTitle)).resolve(folder);
    }

    /** Безопасное имя для папок/файлов. */
    private static String sanitize(String title) {
        if (title == null) title = "";
        String s = title.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim()
                .replace(' ', '_')
                .replaceAll("[._]{2,}", "_");
        if (s.isEmpty()) s = "chat";
        if (s.length() > 80) s = s.substring(0, 80);
        return s;
    }
}
