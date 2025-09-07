package com.oleg.td.dump.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.dump.api.DumpRequest;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.persistence.DatabaseManager;
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
    private final DatabaseManager db;
    private final MediaDownloader downloader;

    private volatile boolean stopRequested = false;

    // Стандартные списки форматов
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
                               DatabaseManager databaseManager,
                               MediaDownloader downloader) {
        this.client = client;
        this.resolver = resolver;
        this.db = databaseManager;
        this.downloader = downloader;
    }

    public void dumpChats(DumpRequest request, Runnable progressCallback) {
        stopRequested = false;

        // Получаем кастомные расширения из запроса
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

            // Переменная для хранения текущих расширений (должна быть объявлена здесь)
            String currentExtensions = null;

            // Определяем lastSavedId для каждого типа контента отдельно
            long lastSavedId = 0;

            // Для сообщений
            if (request.isMessages()) {
                lastSavedId = Math.max(lastSavedId, db.getLastSavedMessageId(chatId));
            }

            // Для фото
            if (request.isPhotos()) {
                lastSavedId = Math.max(lastSavedId, db.getLastSavedPhotoId(chatId));
            }

            // Для видео
            if (request.isVideos()) {
                lastSavedId = Math.max(lastSavedId, db.getLastSavedVideoId(chatId));
            }

            // Для аудио
            if (request.isAudio()) {
                lastSavedId = Math.max(lastSavedId, db.getLastSavedAudioId(chatId));
            }

            // Для документов (с учетом изменения расширений)
            if (request.isTextDocuments()) {
                String lastUsedExtensions = db.loadMetadata(chatId, "text_document_extensions");
                currentExtensions = String.join(",", finalTextExtensions); // Теперь переменная доступна
                boolean extensionsChanged = !currentExtensions.equals(lastUsedExtensions);

                if (extensionsChanged) {
                    // Если расширения изменились, сбрасываем lastSavedId для документов
                    log.info("Чат '{}': расширения изменились, начинаем дамп документов с начала", chatName);
                } else {
                    lastSavedId = Math.max(lastSavedId, db.getLastSavedDocumentId(chatId));
                }
            }

            // Для ссылок
            if (request.isLinks()) {
                lastSavedId = Math.max(lastSavedId, db.getLastSavedLinkId(chatId));
            }

            if (lastSavedId > 0) log.info("Чат '{}': lastSavedId={}", chatName, lastSavedId);
            long fromMessageId = 0;
            boolean reachedAlreadySaved = false;
            int totalMessagesProcessed = 0;
            final int MAX_MESSAGES = 10000;

            while (!stopRequested && !reachedAlreadySaved && totalMessagesProcessed < MAX_MESSAGES) {
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
                    log.info("Чат '{}': достигнут край истории (сообщений больше нет)", chatName);
                    break;
                }

                for (JsonNode msg : messages) {
                    if (stopRequested) break;
                    if (totalMessagesProcessed >= MAX_MESSAGES) break;

                    long mid = msg.path("id").asLong();
                    if (lastSavedId > 0 && mid <= lastSavedId) {
                        reachedAlreadySaved = true;
                        log.info("Чат '{}': достигли уже сохранённых (mid={} <= {}), стоп", chatName, mid, lastSavedId);
                        break;
                    }

                    try {
                        processMessage(chatId, msg, request, finalTextExtensions);
                        totalMessagesProcessed++;
                        if (progressCallback != null) progressCallback.run();
                    } catch (Exception ex) {
                        log.error("Ошибка обработки сообщения {} из чата '{}': {}", mid, chatName, ex.getMessage(), ex);
                    }
                }

                if (!reachedAlreadySaved && totalMessagesProcessed < MAX_MESSAGES) {
                    long oldestMessageId = Long.MAX_VALUE;
                    for (JsonNode msg : messages) {
                        long msgId = msg.path("id").asLong();
                        if (msgId < oldestMessageId) {
                            oldestMessageId = msgId;
                        }
                    }

                    if (oldestMessageId != Long.MAX_VALUE) {
                        fromMessageId = oldestMessageId;
                    } else {
                        break;
                    }

                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // Сохраняем использованные расширения для будущих сравнений
            if (request.isTextDocuments() && currentExtensions != null) {
                db.saveMetadata(chatId, "text_document_extensions", currentExtensions);
            }

            log.info("Дамп чата '{}' завершён. Обработано сообщений: {}", chatName, totalMessagesProcessed);
        }
    }

    private Set<String> parseCustomExtensions(String extensionsString) {
        if (extensionsString == null || extensionsString.trim().isEmpty()) {
            return Set.of();
        }

        return Arrays.stream(extensionsString.split(","))
                .map(String::trim)
                .filter(ext -> !ext.isEmpty())
                .map(ext -> ext.startsWith(".") ? ext.substring(1) : ext)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    public void stop() {
        stopRequested = true;
    }

    private void processMessage(long chatId, JsonNode msg, DumpRequest request, Set<String> textExtensions) {
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
            if (fileId != null && request.isPhotos()) {
                filePath = downloader.downloadBlocking(fileId);
            }

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
            if (fileId != null && request.isVideos()) {
                filePath = downloader.downloadBlocking(fileId);
            }

            db.saveVideo(chatId, messageId, fileId, remoteId, duration, w, h, caption, filePath);
            if (request.isLinks()) extractLinksFromFormattedText(chatId, messageId, content.path("caption"));
        }

        // 4) голос/аудио
        if ("messageVoiceNote".equals(ctype) && request.isAudio()) {
            JsonNode vn = content.path("voice_note");
            Integer duration = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;

            JsonNode fileNode = vn.path("voice");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = vn.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null && request.isAudio()) {
                filePath = downloader.downloadBlocking(fileId);
            }

            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
        }

        if ("messageAudio".equals(ctype) && request.isAudio()) {
            JsonNode au = content.path("audio");
            Integer duration = au.path("duration").isInt() ? au.path("duration").asInt() : null;

            JsonNode fileNode = au.path("audio");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = au.path("mime_type").asText(null);

            String filePath = null;
            if (fileId != null && request.isAudio()) {
                filePath = downloader.downloadBlocking(fileId);
            }

            db.saveAudio(chatId, messageId, fileId, remoteId, duration, mime, filePath);
        }

        // 5) Документы - разделяем на текстовые и аудио
        if ("messageDocument".equals(ctype)) {
            JsonNode document = content.path("document");
            JsonNode captionFT = content.path("caption");
            String caption = captionFT.path("text").asText(null);

            // Извлекаем ссылки из подписи независимо от типа документа
            if (request.isLinks()) {
                extractLinksFromFormattedText(chatId, messageId, captionFT);
            }

            JsonNode fileNode = document.path("document");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String fileName = document.path("file_name").asText(null);
            String mimeType = document.path("mime_type").asText(null);

            // Проверяем тип документа с использованием кастомных расширений
            boolean isTextDocument = isTextDocument(fileName, mimeType, textExtensions);
            boolean isAudioDocument = isAudioDocument(fileName, mimeType);

            String filePath = null;

            // Скачиваем файл ТОЛЬКО если он соответствует запрошенным типам
            if (fileId != null) {
                if ((isTextDocument && request.isTextDocuments()) ||
                        (isAudioDocument && request.isAudio())) {
                    filePath = downloader.downloadBlocking(fileId);
                }
            }

            if (isTextDocument && request.isTextDocuments()) {
                db.saveDocument(chatId, messageId, fileId, remoteId, fileName, mimeType, filePath);
                log.debug("Сохранён текстовый документ: {} (msg_id={})", fileName, messageId);
            } else if (isAudioDocument && request.isAudio()) {
                Integer duration = null;
                db.saveAudio(chatId, messageId, fileId, remoteId, duration, mimeType, filePath);
                log.debug("Сохранён аудио документ: {} (msg_id={})", fileName, messageId);
            } else {
                log.debug("Пропущен документ (не текст и не аудио): {} (msg_id={})", fileName, messageId);
            }
        }
    }

    private boolean isTextDocument(String fileName, String mimeType, Set<String> textExtensions) {
        // Если указаны кастомные расширения, используем только их
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (textExtensions.contains(ext)) {
                return true;
            }
        }

        // Если кастомные расширения не указаны (используются дефолтные), проверяем MIME-типы
        if (mimeType != null && textExtensions.equals(DEFAULT_TEXT_DOCUMENT_EXTENSIONS)) {
            String baseMimeType = mimeType.split(";")[0].trim();
            if (TEXT_MIME_TYPES.contains(baseMimeType) || baseMimeType.startsWith("text/")) {
                return true;
            }
        }

        return false;
    }

    private boolean isAudioDocument(String fileName, String mimeType) {
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (AUDIO_DOCUMENT_EXTENSIONS.contains(ext)) {
                return true;
            }
        }

        if (mimeType != null) {
            String baseMimeType = mimeType.split(";")[0].trim();
            if (AUDIO_MIME_TYPES.contains(baseMimeType) || baseMimeType.startsWith("audio/")) {
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