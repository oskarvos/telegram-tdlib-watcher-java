package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;
    private final MediaDownloader downloader;

    private volatile boolean stopRequested = false;

    // Список текстовых форматов для загрузки
    private static final Set<String> TEXT_DOCUMENT_EXTENSIONS = Set.of(
            "txt", "pdf", "doc", "docx", "rtf", "odt",
            "py", "java", "cpp", "c", "h", "hpp", "js", "html", "css", "xml", "json",
            "bat", "sh", "cmd", "ps1", "md", "csv", "log", "ini", "cfg", "conf"
    );

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf", "application/vnd.oasis.opendocument.text"
    );

    public ChatDumpCoordinator(TdJsonClient client,
                               ChatResolver resolver,
                               DatabaseManager databaseManager,
                               MediaDownloader downloader) {
        this.client = client;
        this.resolver = resolver;
        this.db = databaseManager;
        this.downloader = downloader;
    }

    public void dumpChats(DumpRequest request, Runnable progressCallback) {
        stopRequested = false;

        for (String chatRef : request.getChats()) {
            if (stopRequested) {
                log.info("Дамп прерван пользователем");
                break;
            }

            long chatId = resolver.resolveFlexible(chatRef.trim());
            log.info("Начинаем дамп чата {} (ref='{}')", chatId, chatRef);

            db.prepareSchema(chatId);

            // ключевое изменение: lastSavedId считаем по messages, а если их не сохраняем — по медиа
            long lastSavedId = request.isMessages()
                    ? db.getLastSavedMessageId(chatId)
                    : db.getMaxMediaId(chatId);

            if (lastSavedId > 0) log.info("Чат {}: lastSavedId={}", chatId, lastSavedId);

            long fromMessageId = 0; // 0 — начинать с последних
            boolean reachedAlreadySaved = false;

            while (!stopRequested && !reachedAlreadySaved) {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getChatHistory");
                req.put("chat_id", chatId);
                req.put("from_message_id", fromMessageId);
                req.put("offset", 0);
                req.put("limit", 100);
                req.put("only_local", false);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(req, 60, TdJsonClient.Channel.MAIN);

                if (!"messages".equals(resp.path("@type").asText())) {
                    log.warn("Ответ TDLib отличен от 'messages': {}", resp.path("@type").asText());
                    break;
                }

                ArrayNode messages = (ArrayNode) resp.path("messages");
                if (messages == null || messages.size() == 0) {
                    log.info("Чат {}: достигнут край истории (сообщений больше нет)", chatId);
                    break;
                }

                for (JsonNode msg : messages) {
                    if (stopRequested) break;

                    long mid = msg.path("id").asLong();
                    if (lastSavedId > 0 && mid <= lastSavedId) {
                        reachedAlreadySaved = true;
                        log.info("Чат {}: достигли уже сохранённых (mid={} <= {}), стоп", chatId, mid, lastSavedId);
                        break;
                    }

                    try {
                        processMessage(chatId, msg, request);
                        if (progressCallback != null) progressCallback.run();
                    } catch (Exception ex) {
                        log.error("Ошибка обработки сообщения {} из чата {}: {}", mid, chatId, ex.getMessage(), ex);
                    }
                }

                if (!reachedAlreadySaved) {
                    fromMessageId = messages.get(messages.size() - 1).path("id").asLong();
                }
            }

            log.info("Дамп чата {} завершён", chatId);
        }
    }

    public void stop() {
        stopRequested = true;
    }

    private void processMessage(long chatId, JsonNode msg, DumpRequest request) {
        long messageId = msg.path("id").asLong();
        long date = msg.path("date").asLong(0);
        String senderId = msg.path("sender_id").isMissingNode() ? null : msg.path("sender_id").toString();

        Long replyTo = null;
        JsonNode reply = msg.path("reply_to");
        if (!reply.isMissingNode()) {
            replyTo = reply.path("reply_to_message_id").asLong(0);
            if (replyTo == 0) replyTo = null;
        }

        JsonNode content = msg.path("content");
        String ctype = content.path("@type").asText();

        // 1) текст + ссылки
        if ("messageText".equals(ctype)) {
            JsonNode ft = content.path("text");
            String plainText = ft.path("text").asText(null);

            if (request.isMessages()) db.saveMessage(chatId, messageId, date, senderId, replyTo, plainText);
            if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, ft);
        } else {
            if (request.isMessages()) db.saveMessage(chatId, messageId, date, senderId, replyTo, null);
        }

        // 2) фото
        if ("messagePhoto".equals(ctype) && request.isPhotos()) {
            JsonNode photo = content.path("photo");
            JsonNode captionFT = content.path("caption");
            String caption = captionFT.path("text").asText(null);

            JsonNode sizes = photo.path("sizes");
            JsonNode best = sizes.isArray() && sizes.size() > 0 ? sizes.get(sizes.size() - 1) : null;
            Integer w = best == null ? null : (best.path("width").isInt() ? best.path("width").asInt() : null);
            Integer h = best == null ? null : (best.path("height").isInt() ? best.path("height").asInt() : null);

            JsonNode fileNode = best == null ? null : best.path("photo");
            Integer fileId = fileNode == null ? null : (fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null);
            String remoteId = fileNode == null ? null : fileNode.path("remote").path("id").asText(null);

            String filePath = null;
            if (fileId != null) filePath = downloader.downloadBlocking(fileId);

            db.savePhoto(chatId, messageId, fileId, remoteId, w, h, caption, filePath);
            if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, captionFT);
        }

        // 3) видео
        if ("messageVideo".equals(ctype) && request.isVideos()) {
            JsonNode video = content.path("video");
            String caption = content.path("caption").path("text").asText(null);
            Integer duration = video.path("duration").isInt() ? video.path("duration").asInt() : null;
            Integer w = video.path("width").isInt() ? video.path("width").asInt() : null;
            Integer h = video.path("height").isInt() ? video.path("height").asInt() : null;

            JsonNode fileNode = video.path("video");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);

            String filePath = null;
            if (fileId != null) filePath = downloader.downloadBlocking(fileId);

            db.saveVideo(chatId, messageId, fileId, remoteId, duration, w, h, caption, filePath);
            if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, content.path("caption"));
        }

        // 4) голос/аудио
        if ("messageVoiceNote".equals(ctype) && request.isMessages()) {
            JsonNode vn = content.path("voice_note");
            Integer duration = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;

            JsonNode fileNode = vn.path("voice");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = vn.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null) filePath = downloader.downloadBlocking(fileId);

            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
        }

        if ("messageAudio".equals(ctype) && request.isMessages()) {
            JsonNode au = content.path("audio");
            Integer duration = au.path("duration").isInt() ? au.path("duration").asInt() : null;

            JsonNode fileNode = au.path("audio");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = au.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null) filePath = downloader.downloadBlocking(fileId);

            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
        }

        // 5) Документы (текстовые файлы)
        if ("messageDocument".equals(ctype) && request.isDocuments()) {
            JsonNode document = content.path("document");
            String caption = content.path("caption").path("text").asText(null);

            JsonNode fileNode = document.path("document");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String fileName = document.path("file_name").asText(null);
            String mimeType = document.path("mime_type").asText(null);

            // Проверяем, является ли файл текстовым
            boolean isTextDocument = isTextDocument(fileName, mimeType);

            if (isTextDocument) {
                String filePath = null;
                if (fileId != null) {
                    filePath = downloader.downloadBlocking(fileId);
                }

                db.saveDocument(chatId, messageId, fileId, remoteId, fileName, mimeType, filePath);
                if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, content.path("caption"));

                log.debug("Сохранён текстовый документ: {} (msg_id={})", fileName, messageId);
            } else {
                log.debug("Пропущен нетекстовый документ: {} (msg_id={})", fileName, messageId);
            }
        }
    }

    private boolean isTextDocument(String fileName, String mimeType) {
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (TEXT_DOCUMENT_EXTENSIONS.contains(ext)) {
                return true;
            }
        }

        if (mimeType != null) {
            String baseMimeType = mimeType.split(";")[0].trim();
            if (TEXT_MIME_TYPES.contains(baseMimeType) || baseMimeType.startsWith("text/")) {
                return true;
            }
        }

        return false;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
    }

    private void extractLinksFromFormattedText(long chatId, long messageId, JsonNode formattedText) {
        if (formattedText == null || formattedText.isMissingNode()) return;

        String fullText = formattedText.path("text").asText("");
        JsonNode entities = formattedText.path("entities");
        if (!entities.isArray()) return;

        for (JsonNode e : entities) {
            String t = e.path("type").path("@type").asText();
            if ("textEntityTypeUrl".equals(t)) {
                int offset = e.path("offset").asInt(0);
                int length = e.path("length").asInt(0);
                String url = safeSubstring(fullText, offset, length);
                if (url != null && !url.isBlank()) db.saveLink(chatId, messageId, url, url);
            } else if ("textEntityTypeTextUrl".equals(t)) {
                String url = e.path("type").path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    int offset = e.path("offset").asInt(0);
                    int length = e.path("length").asInt(0);
                    String context = safeSubstring(fullText, offset, length);
                    db.saveLink(chatId, messageId, url, context);
                }
            }
        }
    }

    private static String safeSubstring(String s, int offset, int length) {
        if (s == null || offset < 0 || length <= 0 || offset >= s.length()) return null;
        int end = Math.min(s.length(), offset + length);
        return s.substring(offset, end);
    }
}