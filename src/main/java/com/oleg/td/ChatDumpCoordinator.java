package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Координатор дампа чатов:
 *  - создаёт схему под чат
 *  - постранично запрашивает историю
 *  - сохраняет сообщения/медиа/ссылки
 */
@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DatabaseManager db;
    private final MediaDownloader downloader;

    private volatile boolean stopRequested = false;

    public ChatDumpCoordinator(TdJsonClient client,
                               ChatResolver resolver,
                               DatabaseManager databaseManager,
                               MediaDownloader downloader) {
        this.client = client;
        this.resolver = resolver;
        this.db = databaseManager;
        this.downloader = downloader;
    }

    /** Запускает выгрузку для набора чатов. */
    public void dumpChats(DumpRequest request, Runnable progressCallback) {
        stopRequested = false;

        for (String chatRef : request.getChats()) {
            if (stopRequested) {
                log.info("Дамп прерван пользователем");
                break;
            }

            long chatId = resolver.resolveOrJoin(chatRef.trim());
            log.info("Начинаем дамп чата {} (ref='{}')", chatId, chatRef);

            // Создать/мигрировать таблицы под этот чат
            db.prepareSchema(chatId);

            long fromMessageId = 0; // 0 = начать с последнего (TDLib)
            while (!stopRequested) {
                ObjectNode req = MAPPER.createObjectNode();
                req.put("@type", "getChatHistory");
                req.put("chat_id", chatId);
                req.put("from_message_id", fromMessageId);
                req.put("offset", 0);
                req.put("limit", 100);
                req.put("only_local", false);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(
                        req, 60, TdJsonClient.Channel.MAIN);

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
                    try {
                        processMessage(chatId, msg, request);
                        if (progressCallback != null) progressCallback.run();
                    } catch (Exception ex) {
                        long mid = msg.path("id").asLong();
                        log.error("Ошибка обработки сообщения {} из чата {}: {}", mid, chatId, ex.getMessage(), ex);
                    }
                }

                // Продолжаем от самого старого id из текущей пачки (TDLib сортирует по убыванию)
                fromMessageId = messages.get(messages.size() - 1).path("id").asLong();
            }

            log.info("Дамп чата {} завершён", chatId);
        }
    }

    /** Запрос на остановку дампа. */
    public void stop() {
        stopRequested = true;
    }

    /** Разобрать одно сообщение и сохранить его части в БД. */
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

        // 1) Текст + ссылки
        String plainText = null;
        if ("messageText".equals(ctype)) {
            JsonNode ft = content.path("text"); // formattedText
            plainText = ft.path("text").asText(null);

            if (request.isMessages()) {
                db.saveMessage(chatId, messageId, date, senderId, replyTo, plainText);
            }
            if (request.isLinks()) {
                extractLinksFromFormattedText(chatId, messageId, ft);
            }
        } else {
            // Если не текст — всё равно сохраним запись в messages, но без текста
            if (request.isMessages()) {
                db.saveMessage(chatId, messageId, date, senderId, replyTo, null);
            }
        }

        // 2) Фото
        if ("messagePhoto".equals(ctype) && request.isPhotos()) {
            JsonNode photo = content.path("photo");
            JsonNode captionFT = content.path("caption");
            String caption = captionFT.path("text").asText(null);

            JsonNode sizes = photo.path("sizes");
            JsonNode best = sizes.isArray() && sizes.size() > 0 ? sizes.get(sizes.size() - 1) : null;
            Integer w = best == null ? null : (best.path("width").isInt() ? best.path("width").asInt() : null);
            Integer h = best == null ? null : (best.path("height").isInt() ? best.path("height").asInt() : null);

            JsonNode fileNode = best == null ? null : best.path("photo");
            Integer fileId = (fileNode == null || !fileNode.path("id").isInt()) ? null : fileNode.path("id").asInt();
            String remoteId = fileNode == null ? null : fileNode.path("remote").path("id").asText(null);

            String filePath = null;
            if (fileId != null) {
                filePath = downloader.downloadBlocking(fileId);
            }
            db.savePhoto(chatId, messageId, fileId, remoteId, w, h, caption, filePath);

            if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, captionFT);
        }

        // 3) Видео
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
            if (fileId != null) {
                filePath = downloader.downloadBlocking(fileId);
            }
            db.saveVideo(chatId, messageId, fileId, remoteId, duration, w, h, caption, filePath);

            if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, content.path("caption"));
        }

        // 4) Голосовые/аудио (сохраняем и трек, и путь, и MIME)
        if ("messageVoiceNote".equals(ctype) && request.isMessages()) {
            JsonNode vn = content.path("voice_note");
            Integer duration = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;
            JsonNode fileNode = vn.path("voice");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = vn.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null) {
                filePath = downloader.downloadBlocking(fileId);
            }
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
            if (fileId != null) {
                filePath = downloader.downloadBlocking(fileId);
            }
            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
        }
    }

    /** Извлечь ссылки из formattedText.entities и сохранить. */
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
                if (url != null && !url.isBlank()) {
                    db.saveLink(chatId, messageId, url, url);
                }
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

    /** Безопасная нарезка строки по смещению/длине. */
    private static String safeSubstring(String s, int offset, int length) {
        if (s == null || offset < 0 || length <= 0 || offset >= s.length()) return null;
        int end = Math.min(s.length(), offset + length);
        return s.substring(offset, end);
    }
}
