package com.oleg.td.dump.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.dump.api.DumpRequest;
import com.oleg.td.dump.persistence.DumpDbManager;
import com.oleg.td.dump.persistence.DumpDbSession;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Координатор дампа чатов: ходит в TDLib, сохраняет через DbSession.
 */
@Component
public class ChatDumpCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatDumpCoordinator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;              // TDLib-клиент
    private final ChatResolver resolver;            // резолвер чатов
    private final DumpDbManager db;                 // менеджер БД дампа
    private final MediaDownloader downloader;       // скачивание файлов

    private volatile boolean stopRequested = false; // флаг остановки

    // дефолтные расширения для текстовых документов
    private static final Set<String> DEFAULT_TEXT_DOCUMENT_EXTENSIONS = Set.of(
            "txt","pdf","doc","docx","rtf","odt","py","java","cpp","c","h","hpp",
            "js","html","css","xml","json","bat","sh","cmd","ps1","md","csv","log","ini","cfg","conf"
    );

    // известные аудио-расширения для документов
    private static final Set<String> AUDIO_DOCUMENT_EXTENSIONS = Set.of(
            "mp3","wav","ogg","flac","m4a","aac","wma","aiff","aif","amr","opus","mid",
            "midi","mp2","ac3","ra","rm","wv","ape","tta"
    );

    // MIME-типы текстовых и аудио
    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain","application/pdf","application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/rtf","application/vnd.oasis.opendocument.text"
    );
    private static final Set<String> AUDIO_MIME_TYPES = Set.of(
            "audio/mpeg","audio/wav","audio/ogg","audio/flac","audio/x-flac","audio/mp4",
            "audio/aac","audio/x-ms-wma","audio/aiff","audio/x-aiff","audio/amr",
            "audio/midi","audio/x-midi","audio/x-ape","audio/x-wav"
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

    // запуск дампа по списку чатов; listener может быть пустым
    public void dumpChats(DumpRequest request, DumpListener listener) {
        if (listener == null) listener = new DumpListener() {};
        stopRequested = false;

        // готовим набор расширений для текстовых документов
        Set<String> userTextExts = parseCustomExtensions(request.getTextDocumentExtensions());
        Set<String> effectiveTextExts = userTextExts.isEmpty()
                ? DEFAULT_TEXT_DOCUMENT_EXTENSIONS
                : userTextExts;
        String normalizedCurrentExts = normalizeExtList(effectiveTextExts);
        log.info("Используемые расширения текстовых документов: {}", normalizedCurrentExts);

        for (String chatRef : request.getChats()) {
            if (stopRequested) {
                log.info("Дамп прерван пользователем");
                break;
            }

            long chatId = resolver.resolveFlexible(chatRef.trim());
            String chatName = resolver.getChatTitle(chatId);
            log.info("Начинаем дамп чата '{}' (ID: {})", chatName, chatId); // изменено

            db.prepareSchema(chatId);

            // одна сессия подключения на чат
            try (DumpDbSession session = db.openSession(chatId)) {
                // границы уже сохранённого по типам
                long lastMsgId   = request.isMessages()      ? db.getLastSavedMessageId(chatId)   : Long.MAX_VALUE;
                long lastPhotoId = request.isPhotos()        ? db.getLastSavedPhotoId(chatId)     : Long.MAX_VALUE;
                long lastVideoId = request.isVideos()        ? db.getLastSavedVideoId(chatId)     : Long.MAX_VALUE;
                long lastAudioId = request.isAudio()         ? db.getLastSavedAudioId(chatId)     : Long.MAX_VALUE;
                long lastLinkId  = request.isLinks()         ? db.getLastSavedLinkId(chatId)      : Long.MAX_VALUE;

                // если набор расширений для доков изменился — начинаем документы с нуля
                String storedRaw = session.loadMetadata("text_document_extensions");
                String normalizedStoredExts = normalizeExtList(parseExtList(storedRaw));
                boolean docExtChanged = request.isTextDocuments() && !normalizedCurrentExts.equals(normalizedStoredExts);
                long lastDocId = request.isTextDocuments()
                        ? (docExtChanged ? 0L : db.getLastSavedDocumentId(chatId))
                        : Long.MAX_VALUE;
                if (docExtChanged) {
                    log.info("Чат '{}' (ID: {}): набор расширений изменился ( '{}' → '{}' ), документы с начала",
                            chatName, chatId, normalizedStoredExts, normalizedCurrentExts);
                }

                // флаги «дошли до сохранённого»
                boolean reachedMsgs   = !request.isMessages();
                boolean reachedPhotos = !request.isPhotos();
                boolean reachedVideos = !request.isVideos();
                boolean reachedAudio  = !request.isAudio();
                boolean reachedDocs   = !request.isTextDocuments();
                boolean reachedLinks  = !request.isLinks();

                long fromMessageId = 0; // идём назад от новых к старым

                // основной цикл чтения истории
                while (!stopRequested && !(reachedMsgs && reachedPhotos && reachedVideos && reachedAudio && reachedDocs && reachedLinks)) {
                    // запрос истории
                    ObjectNode reqNode = MAPPER.createObjectNode();
                    reqNode.put("@type", "getChatHistory");
                    reqNode.put("chat_id", chatId);
                    reqNode.put("from_message_id", fromMessageId);
                    reqNode.put("offset", 0);
                    reqNode.put("limit", 100);
                    reqNode.put("only_local", false);

                    long t0 = System.nanoTime();
                    ObjectNode resp = client.requestWithFloodWaitSyncLimited(reqNode, 60, TdJsonClient.Channel.MAIN);
                    long ms = (System.nanoTime() - t0) / 1_000_000L;
                    if (ms > 2000) {
                        log.warn("getChatHistory для '{}' (ID: {}) занял {} ms (возможен flood-wait)", chatName, chatId, ms);
                    }

                    if (!"messages".equals(resp.path("@type").asText())) {
                        log.warn("Ответ TDLib не 'messages' для чата '{}' (ID: {}): {}", chatName, chatId, resp.path("@type").asText());
                        break;
                    }

                    ArrayNode messages = (ArrayNode) resp.path("messages");
                    if (messages == null || messages.size() == 0) {
                        log.info("Чат '{}' (ID: {}): достигнут край истории", chatName, chatId);
                        break;
                    }

                    long oldestInBatch = Long.MAX_VALUE;

                    for (JsonNode msg : messages) {
                        if (stopRequested) break;

                        long mid = msg.path("id").asLong();

                        // отметим достижения порогов
                        if (!reachedMsgs   && mid <= lastMsgId)   reachedMsgs = true;
                        if (!reachedPhotos && mid <= lastPhotoId) reachedPhotos = true;
                        if (!reachedVideos && mid <= lastVideoId) reachedVideos = true;
                        if (!reachedAudio  && mid <= lastAudioId) reachedAudio = true;
                        if (!reachedDocs   && mid <= lastDocId)   reachedDocs = true;
                        if (!reachedLinks  && mid <= lastLinkId)  reachedLinks = true;

                        try {
                            processMessage(session, chatId, msg, request, effectiveTextExts,
                                    lastMsgId, lastLinkId, lastPhotoId, lastVideoId, lastAudioId, lastDocId,
                                    listener);
                        } catch (Exception ex) {
                            log.error("Ошибка обработки сообщения {} в чате '{}' (ID: {}): {}", mid, chatName, chatId, ex.getMessage(), ex);
                        }

                        if (mid < oldestInBatch) oldestInBatch = mid;
                        listener.onProgress(); // прогресс по сообщению
                    }

                    if (oldestInBatch == Long.MAX_VALUE) break; // защитный выход
                    fromMessageId = oldestInBatch;              // пагинация назад

                    try { Thread.sleep(100); }                  // щадящая пауза TDLib
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                // запишем актуальный список расширений
                if (request.isTextDocuments()) {
                    session.saveMetadata("text_document_extensions", normalizedCurrentExts);
                }

                try { session.commit(); } catch (Exception ignore) {} // финальный коммит
            } catch (Exception sessionErr) {
                log.error("Сессия дампа для чата '{}' (ID: {}) завершилась ошибкой: {}", chatName, chatId, sessionErr.getMessage(), sessionErr);
            }

            log.info("Дамп чата '{}' (ID: {}) завершён", chatName, chatId);
        }
    }

    // остановка процесса извне
    public void stop() {
        stopRequested = true;
    }

    // обработка одного сообщения; сохраняем только «новые» по типам
    private void processMessage(DumpDbSession session,
                                long chatId,
                                JsonNode msg,
                                DumpRequest request,
                                Set<String> textExtensions,
                                long lastSavedMessageId,
                                long lastSavedLinkId,
                                long lastSavedPhotoId,
                                long lastSavedVideoId,
                                long lastSavedAudioId,
                                long lastSavedDocumentId,
                                DumpListener listener) {

        long messageId = msg.path("id").asLong();
        long date = msg.path("date").asLong(0);
        String senderId = msg.path("sender_id").isMissingNode() ? null : msg.path("sender_id").toString();

        // извлекаем reply_to, если есть
        Long replyTo = null;
        JsonNode reply = msg.path("reply_to");
        if (!reply.isMissingNode()) {
            long rid = reply.path("reply_to_message_id").asLong(0);
            replyTo = (rid == 0) ? null : rid;
        }

        JsonNode content = msg.path("content");
        String ctype = content.path("@type").asText();

        // 1) текст и ссылки
        if ("messageText".equals(ctype)) {
            JsonNode ft = content.path("text");
            String plain = ft.path("text").asText(null);

            if (request.isMessages() && messageId > lastSavedMessageId) {
                session.saveMessage(messageId, date, senderId, replyTo, plain);
                listener.onSavedMessage();
            }
            if (request.isLinks() && messageId > lastSavedLinkId) {
                int cnt = extractLinksFromFormattedText(session, messageId, ft);
                if (cnt > 0) listener.onSavedLinks(cnt);
            }
        } else {
            if (request.isMessages() && messageId > lastSavedMessageId) {
                session.saveMessage(messageId, date, senderId, replyTo, null);
                listener.onSavedMessage();
            }
        }

        // 2) фото
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

            String filePath = (fileId != null) ? downloader.downloadBlocking(fileId) : null;

            session.savePhoto(messageId, fileId, remoteId, w, h, caption, filePath);
            listener.onSavedPhoto();

            if (request.isLinks() && messageId > lastSavedLinkId) {
                int cnt = extractLinksFromFormattedText(session, messageId, captionFT);
                if (cnt > 0) listener.onSavedLinks(cnt);
            }
        }

        // 3) видео
        if ("messageVideo".equals(ctype) && request.isVideos() && messageId > lastSavedVideoId) {
            JsonNode video = content.path("video");
            String caption = content.path("caption").path("text").asText(null);

            Integer duration = video.path("duration").isInt() ? video.path("duration").asInt() : null;
            Integer w = video.path("width").isInt() ? video.path("width").asInt() : null;
            Integer h = video.path("height").isInt() ? video.path("height").asInt() : null;

            JsonNode fileNode = video.path("video");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);

            String filePath = (fileId != null) ? downloader.downloadBlocking(fileId) : null;

            session.saveVideo(messageId, fileId, remoteId, duration, w, h, caption, filePath);
            listener.onSavedVideo();

            if (request.isLinks() && messageId > lastSavedLinkId) {
                int cnt = extractLinksFromFormattedText(session, messageId, content.path("caption"));
                if (cnt > 0) listener.onSavedLinks(cnt);
            }
        }

        // 4) голос/аудио
        if ("messageVoiceNote".equals(ctype) && request.isAudio() && messageId > lastSavedAudioId) {
            JsonNode vn = content.path("voice_note");
            Integer duration = vn.path("duration").isInt() ? vn.path("duration").asInt() : null;

            JsonNode fileNode = vn.path("voice");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = vn.path("mime_type").asText(null);

            String filePath = (fileId != null) ? downloader.downloadBlocking(fileId) : null;

            session.saveAudio(messageId, fileId, remoteId, duration, mime, filePath);
            listener.onSavedAudio();
        }

        if ("messageAudio".equals(ctype) && request.isAudio() && messageId > lastSavedAudioId) {
            JsonNode au = content.path("audio");
            Integer duration = au.path("duration").isInt() ? au.path("duration").asInt() : null;

            JsonNode fileNode = au.path("audio");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String mime = au.path("mime_type").asText(null);

            String filePath = (fileId != null) ? downloader.downloadBlocking(fileId) : null;

            session.saveAudio(messageId, fileId, remoteId, duration, mime, filePath);
            listener.onSavedAudio();
        }

        // 5) документы (текстовые и аудио)
        if ("messageDocument".equals(ctype)) {
            JsonNode document = content.path("document");
            JsonNode captionFT = content.path("caption");

            if (request.isLinks() && messageId > lastSavedLinkId) {
                int cnt = extractLinksFromFormattedText(session, messageId, captionFT);
                if (cnt > 0) listener.onSavedLinks(cnt);
            }

            JsonNode fileNode = document.path("document");
            Integer fileId = fileNode.path("id").isInt() ? fileNode.path("id").asInt() : null;
            String remoteId = fileNode.path("remote").path("id").asText(null);
            String fileName = document.path("file_name").asText(null);
            String mimeType = document.path("mime_type").asText(null);

            boolean isTextDoc  = isTextDocument(fileName, mimeType, textExtensions);
            boolean isAudioDoc = isAudioDocument(fileName, mimeType);

            boolean saveText  = request.isTextDocuments() && isTextDoc  && messageId > lastSavedDocumentId;
            boolean saveAudio = request.isAudio()         && isAudioDoc && messageId > lastSavedAudioId;

            String filePath = null;
            if (fileId != null && (saveText || saveAudio)) filePath = downloader.downloadBlocking(fileId);

            if (saveText) {
                session.saveDocument(messageId, fileId, remoteId, fileName, mimeType, filePath);
                String ext = getFileExtension(fileName).toLowerCase();
                if (ext.isBlank()) ext = "unknown";
                listener.onSavedDocument(ext);
            } else if (saveAudio) {
                session.saveAudio(messageId, fileId, remoteId, null, mimeType, filePath);
                listener.onSavedAudio();
            }
        }
    }

    // распознаём «текстовый документ» по расширению/минимальному набору MIME
    private boolean isTextDocument(String fileName, String mimeType, Set<String> textExtensions) {
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (textExtensions.contains(ext)) return true;
        }
        // используем MIME только с дефолтным набором расширений
        if (mimeType != null && textExtensions.equals(DEFAULT_TEXT_DOCUMENT_EXTENSIONS)) {
            String base = mimeType.split(";")[0].trim();
            if (TEXT_MIME_TYPES.contains(base) || base.startsWith("text/")) return true;
        }
        return false;
    }

    // распознаём «аудио-документ»
    private boolean isAudioDocument(String fileName, String mimeType) {
        if (fileName != null) {
            String ext = getFileExtension(fileName).toLowerCase();
            if (AUDIO_DOCUMENT_EXTENSIONS.contains(ext)) return true;
        }
        if (mimeType != null) {
            String base = mimeType.split(";")[0].trim();
            if (AUDIO_MIME_TYPES.contains(base) || base.startsWith("audio/")) return true;
        }
        return false;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int i = fileName.lastIndexOf('.');          // проверяет наличие точки
        return (i == -1) ? "" : fileName.substring(i + 1);
    }

    // сохранение ссылок из форматированного текста; возвращает кол-во сохранённых
    private int extractLinksFromFormattedText(DumpDbSession session, long messageId, JsonNode formattedText) {
        if (formattedText == null || formattedText.isMissingNode()) return 0;

        String full = formattedText.path("text").asText("");
        JsonNode entities = formattedText.path("entities");
        if (!entities.isArray()) return 0;

        int saved = 0;
        for (JsonNode e : entities) {
            String t = e.path("type").path("@type").asText();
            if ("textEntityTypeUrl".equals(t)) {
                int off = e.path("offset").asInt(0);
                int len = e.path("length").asInt(0);
                String url = safeSubstring(full, off, len);
                if (url != null && !url.isBlank()) {
                    session.saveLink(messageId, url, url);
                    saved++;
                }
            } else if ("textEntityTypeTextUrl".equals(t)) {
                String url = e.path("type").path("url").asText(null);
                if (url != null && !url.isBlank()) {
                    int off = e.path("offset").asInt(0);
                    int len = e.path("length").asInt(0);
                    String ctx = safeSubstring(full, off, len);
                    session.saveLink(messageId, url, ctx);
                    saved++;
                }
            }
        }
        return saved;
    }

    private static String safeSubstring(String s, int offset, int length) {
        if (s == null || offset < 0 || length <= 0 || offset >= s.length()) return null; // проверяет границы
        int end = Math.min(s.length(), offset + length);
        return s.substring(offset, end);
    }

    // разбор пользовательского списка расширений
    private Set<String> parseCustomExtensions(String s) {
        if (s == null || s.trim().isEmpty()) return Set.of();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.startsWith(".") ? v.substring(1) : v)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    // нормализация списков расширений (для сравнения/сохранения)
    private Set<String> parseExtList(String s) {
        if (s == null || s.isBlank()) return Set.of();
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.startsWith(".") ? v.substring(1) : v)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private String normalizeExtList(Set<String> exts) {
        if (exts == null || exts.isEmpty()) return "";
        return exts.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.startsWith(".") ? e.substring(1) : e)
                .map(String::toLowerCase)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    public void clearDownloaderCache() {
        try { downloader.clearCache(); } catch (Exception ignore) {}
    }

}
