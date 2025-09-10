package com.oleg.td.dump.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.dump.api.DumpRequest;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.dump.persistence.DumpDbManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;
    private final ChatResolver resolver;
    private final DumpDbManager db;
    private final MediaDownloader downloader;

    private volatile boolean stopRequested = false;

    private static final Set<String> DEFAULT_TEXT_DOCUMENT_EXTENSIONS = Set.of(
            "txt", "pdf", "doc", "docx", "rtf", "odt",
            "py", "java", "cpp", "c", "h", "hpp", "js", "html", "css", "xml", "json",
            "bat", "sh", "cmd", "ps1", "md", "csv", "log", "ini", "cfg", "conf"
    );

    private static final Set<String> AUDIO_DOCUMENT_EXTENSIONS = Set.of(
            "mp3", "wav", "ogg", "flac", "m4a", "aac", "wma", "aiff", "aif", "amr",
            "opus", "mid", "midi", "mp2", "ac3", "ra", "rm", "wv", "ape", "tta"
    );

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "application/pdf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf", "application/vnd.oasis.opendocument.text"
    );

    private static final Set<String> AUDIO_MIME_TYPES = Set.of(
            "audio/mpeg", "audio/wav", "audio/ogg", "audio/flac", "audio/x-flac",
            "audio/mp4", "audio/aac", "audio/x-ms-wma", "audio/aiff", "audio/x-aiff",
            "audio/amr", "audio/midi", "audio/x-midi", "audio/x-ape", "audio/x-wav"
    );

    public ChatDumpCoordinator(TdJsonClient client,
                               ChatResolver resolver,
                               DumpDbManager databaseManager,
                               MediaDownloader downloader) {
        this.client = client;
        this.resolver = resolver;
        this.db = databaseManager;
        this.downloader = downloader;
    }

    public void dumpChats(DumpRequest request, DumpListener listener) {
        stopRequested = false;

        Set<String> customTextExtensions = parseCustomExtensions(request.getTextDocumentExtensions());
        Set<String> finalTextExtensions = customTextExtensions.isEmpty()
                ? DEFAULT_TEXT_DOCUMENT_EXTENSIONS
                : customTextExtensions;
        log.info("Используемые расширения текстовых документов: {}", finalTextExtensions);

        for (String chatRef : request.getChats()) {
            if (stopRequested) {
                log.info("Дамп прерван пользователем");
                break;
            }

            long chatId = resolver.resolveFlexible(chatRef.trim());
            String chatName = resolver.getChatTitle(chatId);
            log.info("Начинаем дамп чата '{}'", chatName);

            db.prepareSchema(chatId);

            long lastMsgId   = request.isMessages()      ? db.getLastSavedMessageId(chatId) : Long.MAX_VALUE;
            long lastPhotoId = request.isPhotos()        ? db.getLastSavedPhotoId(chatId)   : Long.MAX_VALUE;
            long lastVideoId = request.isVideos()        ? db.getLastSavedVideoId(chatId)   : Long.MAX_VALUE;
            long lastAudioId = request.isAudio()         ? db.getLastSavedAudioId(chatId)   : Long.MAX_VALUE;
            long lastLinkId  = request.isLinks()         ? db.getLastSavedLinkId(chatId)    : Long.MAX_VALUE;

            String storedExtensions = db.loadMetadata(chatId, "text_document_extensions");
            String currentExtensions = String.join(",", finalTextExtensions);
            boolean docExtChanged = request.isTextDocuments() && !currentExtensions.equals(storedExtensions);
            long lastDocId = request.isTextDocuments()
                    ? (docExtChanged ? 0L : db.getLastSavedDocumentId(chatId))
                    : Long.MAX_VALUE;

            if (docExtChanged) {
                log.info("Чат '{}': набор расширений документов изменился — начинаем с начала (lastDocId=0)", chatName);
            }

            boolean reachedMsgs   = !request.isMessages();
            boolean reachedPhotos = !request.isPhotos();
            boolean reachedVideos = !request.isVideos();
            boolean reachedAudio  = !request.isAudio();
            boolean reachedDocs   = !request.isTextDocuments();
            boolean reachedLinks  = !request.isLinks();

            if (request.isMessages())      log.info("lastSaved(message)={}",  lastMsgId);
            if (request.isPhotos())        log.info("lastSaved(photo)={}",    lastPhotoId);
            if (request.isVideos())        log.info("lastSaved(video)={}",    lastVideoId);
            if (request.isAudio())         log.info("lastSaved(audio)={}",    lastAudioId);
            if (request.isTextDocuments()) log.info("lastSaved(document)={}", lastDocId);
            if (request.isLinks())         log.info("lastSaved(link)={}",     lastLinkId);

            long fromMessageId = 0;
            int  totalMessagesProcessed = 0;

            while (!stopRequested && !(reachedMsgs && reachedPhotos && reachedVideos && reachedAudio && reachedDocs && reachedLinks)) {

                ObjectNode reqNode = MAPPER.createObjectNode();
                reqNode.put("@type", "getChatHistory");
                reqNode.put("chat_id", chatId);
                reqNode.put("from_message_id", fromMessageId);
                reqNode.put("offset", 0);
                reqNode.put("limit", 100);
                reqNode.put("only_local", false);

                ObjectNode resp = client.requestWithFloodWaitSyncLimited(reqNode, 60, TdJsonClient.Channel.MAIN);
                if (!"messages".equals(resp.path("@type").asText())) {
                    log.warn("Ответ TDLib отличен от 'messages': {}", resp.path("@type").asText());
                    break;
                }

                ArrayNode messages = (ArrayNode) resp.path("messages");
                if (messages == null || messages.size() == 0) {
                    log.info("Чат '{}': достигнут край истории (сообщений больше нет)", chatName);
                    break;
                }

                long oldestMessageIdInBatch = Long.MAX_VALUE;

                for (JsonNode msg : messages) {
                    if (stopRequested) break;

                    long mid = msg.path("id").asLong();

                    if (!reachedMsgs   && mid <= lastMsgId)   reachedMsgs   = true;
                    if (!reachedPhotos && mid <= lastPhotoId) reachedPhotos = true;
                    if (!reachedVideos && mid <= lastVideoId) reachedVideos = true;
                    if (!reachedAudio  && mid <= lastAudioId) reachedAudio  = true;
                    if (!reachedDocs   && mid <= lastDocId)   reachedDocs   = true;
                    if (!reachedLinks  && mid <= lastLinkId)  reachedLinks  = true;

                    try {
                        processMessage(chatId, msg, request, finalTextExtensions,
                                lastMsgId, lastLinkId, lastPhotoId, lastVideoId, lastAudioId, lastDocId, listener);
                        totalMessagesProcessed++;
                        if (listener != null) listener.onProgress();
                    } catch (Exception ex) {
                        log.error("Ошибка обработки сообщения {} из чата '{}': {}", mid, chatName, ex.getMessage(), ex);
                    }

                    if (mid < oldestMessageIdInBatch) oldestMessageIdInBatch = mid;
                }

                if (oldestMessageIdInBatch != Long.MAX_VALUE) {
                    fromMessageId = oldestMessageIdInBatch;
                } else {
                    break;
                }

                try { Thread.sleep(100); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }

            if (request.isTextDocuments()) {
                db.saveMetadata(chatId, "text_document_extensions", currentExtensions);
            }

            log.info("Дамп чата '{}' завершён. Обработано сообщений: {}", chatName, totalMessagesProcessed);
        }
    }

    private Set<String> parseCustomExtensions(String extensionsString) {
        if (extensionsString == null || extensionsString.trim().isEmpty()) return Set.of();
        return Arrays.stream(extensionsString.split(","))
                .map(String::trim)
                .filter(ext -> !ext.isEmpty())
                .map(ext -> ext.startsWith(".") ? ext.substring(1) : ext)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    public void stop() { stopRequested = true; }

    private void processMessage(
            long chatId,
            JsonNode msg,
            com.oleg.td.dump.api.DumpRequest request,
            Set<String> textExtensions,
            long lastSavedMessageId,
            long lastSavedLinkId,
            long lastSavedPhotoId,
            long lastSavedVideoId,
            long lastSavedAudioId,
            long lastSavedDocumentId,
            DumpListener listener
    ) {
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

        if ("messageText".equals(ctype)) {
            JsonNode ft = content.path("text");
            String plainText = ft.path("text").asText(null);

            if (request.isMessages() && messageId > lastSavedMessageId) {
                db.saveMessage(chatId, messageId, date, senderId, replyTo, plainText);
                if (listener != null) listener.onSavedMessage();
            }
            if (request.isLinks() && messageId > lastSavedLinkId) {
                int n = extractLinksFromFormattedText(chatId, messageId, ft);
                if (n > 0 && listener != null) listener.onSavedLinks(n);
            }
        } else {
            if (request.isMessages() && messageId > lastSavedMessageId) {
                db.saveMessage(chatId, messageId, date, senderId, replyTo, null);
                if (listener != null) listener.onSavedMessage();
            }
        }

        if ("messagePhoto".equals(ctype) && request.isPhotos() && messageId > lastSavedPhotoId) {
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
            if (listener != null) listener.onSavedPhoto();

            if (request.isLinks() && messageId > lastSavedLinkId) {
                int n = extractLinksFromFormattedText(chatId, messageId, captionFT);
                if (n > 0 && listener != null) listener.onSavedLinks(n);
            }
        }

        if ("messageVideo".equals(ctype) && request.isVideos() && messageId > lastSavedVideoId) {
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
            if (listener != null) listener.onSavedVideo();

            if (request.isLinks() && messageId > lastSavedLinkId) {
                int n = extractLinksFromFormattedText(chatId, messageId, content.path("caption"));
                if (n > 0 && listener != null) listener.onSavedLinks(n);
            }
        }

        if ("messageVoiceNote".equals(ctype) && request.isAudio() && messageId > lastSavedAudioId) {
            JsonNode vn = content.path("voice_note");
            Integer duration = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;

            JsonNode fileNode = vn.path("voice");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = vn.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null) filePath = downloader.downloadBlocking(fileId);

            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
            if (listener != null) listener.onSavedAudio();
        }

        if ("messageAudio".equals(ctype) && request.isAudio() && messageId > lastSavedAudioId) {
            JsonNode au = content.path("audio");
            Integer duration = au.path("duration").isInt() ? au.path("duration").asInt() : null;

            JsonNode fileNode = au.path("audio");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = au.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null) filePath = downloader.downloadBlocking(fileId);

            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
            if (listener != null) listener.onSavedAudio();
        }

        if ("messageDocument".equals(ctype)) {
            JsonNode document = content.path("document");
            JsonNode captionFT = content.path("caption");

            if (request.isLinks() && messageId > lastSavedLinkId) {
                int n = extractLinksFromFormattedText(chatId, messageId, captionFT);
                if (n > 0 && listener != null) listener.onSavedLinks(n);
            }

            JsonNode fileNode = document.path("document");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String fileName = document.path("file_name").asText(null);
            String mimeType = document.path("mime_type").asText(null);

            boolean isTextDocument = isTextDocument(fileName, mimeType, textExtensions);
            boolean isAudioDocument = isAudioDocument(fileName, mimeType);

            boolean shouldSaveTextDoc  = request.isTextDocuments() && isTextDocument && messageId > lastSavedDocumentId;
            boolean shouldSaveAudioDoc = request.isAudio()         && isAudioDocument && messageId > lastSavedAudioId;

            String filePath = null;
            if (fileId != null && (shouldSaveTextDoc || shouldSaveAudioDoc)) {
                filePath = downloader.downloadBlocking(fileId);
            }

            if (shouldSaveTextDoc) {
                db.saveDocument(chatId, messageId, fileId, remoteId, fileName, mimeType, filePath);
                if (listener != null) {
                    String ext = getFileExtension(fileName).toLowerCase();
                    if (ext == null || ext.isBlank()) ext = "unknown";
                    listener.onSavedDocument(ext);
                }
            } else if (shouldSaveAudioDoc) {
                Integer duration = null;
                db.saveAudio(chatId, messageId, fileId, remoteId, duration, mimeType, filePath);
                if (listener != null) listener.onSavedAudio();
            }
        }
    }

    private boolean isTextDocument(String fileName, String mimeType, Set<String> textExtensions) {
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (textExtensions.contains(ext)) return true;
        }
        if (mimeType != null && textExtensions.equals(DEFAULT_TEXT_DOCUMENT_EXTENSIONS)) {
            String baseMimeType = mimeType.split(";")[0].trim();
            if (TEXT_MIME_TYPES.contains(baseMimeType) || baseMimeType.startsWith("text/")) return true;
        }
        return false;
    }

    private boolean isAudioDocument(String fileName, String mimeType) {
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (AUDIO_DOCUMENT_EXTENSIONS.contains(ext)) return true;
        }
        if (mimeType != null) {
            String baseMimeType = mimeType.split(";")[0].trim();
            if (AUDIO_MIME_TYPES.contains(baseMimeType) || baseMimeType.startsWith("audio/")) return true;
        }
        return false;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex == -1) ? "" : fileName.substring(dotIndex + 1);
    }

    private int extractLinksFromFormattedText(long chatId, long messageId, JsonNode formattedText) {
        if (formattedText == null || formattedText.isMissingNode()) return 0;
        String fullText = formattedText.path("text").asText("");
        JsonNode entities = formattedText.path("entities");
        if (!entities.isArray()) return 0;

        int saved = 0;
        for (JsonNode e : entities) {
            String t = e.path("type").path("@type").asText();
            if ("textEntityTypeUrl".equals(t)) {
                int offset = e.path("offset").asInt(0);
                int length = e.path("length").asInt(0);
                String url = safeSubstring(fullText, offset, length);
                if (url != null && !url.isBlank()) {
                    db.saveLink(chatId, messageId, url, url);
                    saved++;
                }
            } else if ("textEntityTypeTextUrl".equals(t)) {
                String url = e.path("type").path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    int offset = e.path("offset").asInt(0);
                    int length = e.path("length").asInt(0);
                    String context = safeSubstring(fullText, offset, length);
                    db.saveLink(chatId, messageId, url, context);
                    saved++;
                }
            }
        }
        return saved;
    }

    private static String safeSubstring(String s, int offset, int length) {
        if (s == null || offset < 0 || length <= 0 || offset >= s.length()) return null;
        int end = Math.min(s.length(), offset + length);
        return s.substring(offset, end);
    }
}
