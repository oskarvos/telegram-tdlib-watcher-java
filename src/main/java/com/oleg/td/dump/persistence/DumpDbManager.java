// src/main/java/com/oleg/td/dump/persistence/DumpDbManager.java
package com.oleg.td.dump.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.regex.Pattern;

/**
 * Менеджер DUMP-БД: схема, сессии, сохранение и очистка.
 */
@Component
public class DumpDbManager {

    private static final Logger log = LoggerFactory.getLogger(DumpDbManager.class);

    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|]"); // недопустимые символы

    private final Path dbDir    = Paths.get("tdlib", "db");    // каталог БД
    private final Path filesDir = Paths.get("tdlib", "files"); // каталог файлов
    private final ChatResolver chatResolver;                               // заголовки чатов

    public DumpDbManager(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
        try { Files.createDirectories(dbDir); }   catch (Exception ignore) {}
        try { Files.createDirectories(filesDir);} catch (Exception ignore) {}
    }

    // открывает сессию для чата
    public DbSession openSession(long chatId) throws SQLException {
        Connection c = openDump(chatId);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA busy_timeout=5000");
        }
        c.setAutoCommit(false);
        return new DbSession(chatId, c);
    }

    // гарантирует таблицу metadata (внутренний помощник)
    private void ensureDumpMetadata(long chatId) {
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e) {
            log.error("DUMP {}: ошибка создания таблицы metadata: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    // готовит схему БД (таблицы, индексы, дедупликация)
    public void prepareSchema(long chatId) {
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "date INTEGER," +
                    "sender_id TEXT," +
                    "reply_to INTEGER," +
                    "text TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_messages_mid ON messages(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS photos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "width INTEGER," +
                    "height INTEGER," +
                    "caption TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_photos_mid ON photos(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS videos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "duration INTEGER," +
                    "width INTEGER," +
                    "height INTEGER," +
                    "caption TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_videos_mid ON videos(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS audio (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "duration INTEGER," +
                    "mime TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_audio_mid ON audio(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS documents (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "file_name TEXT," +
                    "mime_type TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_documents_mid ON documents(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS links (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "url TEXT," +
                    "context TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_links_mid ON links(message_id)");

            ensureDumpMetadata(chatId);

            dedupTable(c, "messages",  "message_id");
            dedupTable(c, "photos",    "message_id");
            dedupTable(c, "videos",    "message_id");
            dedupTable(c, "audio",     "message_id");
            dedupTable(c, "documents", "message_id");
            dedupTable(c, "links",     "message_id, url");

            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_mid  ON messages(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_photos_mid    ON photos(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_videos_mid    ON videos(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_audio_mid     ON audio(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_documents_mid ON documents(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_links_mid_url ON links(message_id, url)");
        } catch (SQLException e) {
            log.error("DUMP {}: ошибка подготовки схемы: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    // возвращает максимальный сохранённый message_id для таблицы
    public long getLastSavedMessageId(long chatId)  { try (Connection c = openDump(chatId)) { return maxOf(c, "messages");  } catch (SQLException e) { log.warn("Последний messages: {}", e.getMessage());  return 0L; } }
    public long getLastSavedPhotoId(long chatId)    { try (Connection c = openDump(chatId)) { return maxOf(c, "photos");    } catch (SQLException e) { log.warn("Последний photos: {}", e.getMessage());    return 0L; } }
    public long getLastSavedVideoId(long chatId)    { try (Connection c = openDump(chatId)) { return maxOf(c, "videos");    } catch (SQLException e) { log.warn("Последний videos: {}", e.getMessage());    return 0L; } }
    public long getLastSavedAudioId(long chatId)    { try (Connection c = openDump(chatId)) { return maxOf(c, "audio");     } catch (SQLException e) { log.warn("Последний audio: {}", e.getMessage());     return 0L; } }
    public long getLastSavedDocumentId(long chatId) { try (Connection c = openDump(chatId)) { return maxOf(c, "documents"); } catch (SQLException e) { log.warn("Последний documents: {}", e.getMessage()); return 0L; } }
    public long getLastSavedLinkId(long chatId)     { try (Connection c = openDump(chatId)) { return maxOf(c, "links");     } catch (SQLException e) { log.warn("Последний links: {}", e.getMessage());     return 0L; } }

    // удаляет все DUMP-БД и очищает tdlib/files/*
    public void clearDumpDatabasesAndDeleteFiles() {
        try { Files.createDirectories(dbDir); } catch (Exception ignore) {}
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "DUMP *.db")) {
            for (Path p : ds) {
                deleteDbWithSidecars(p);
                log.info("Удалён файл DUMP-БД: {}", p.getFileName());
            }
        } catch (IOException e) {
            log.error("Ошибка обхода каталога с БД: {}", e.getMessage(), e);
        }

        try { Files.createDirectories(filesDir); } catch (Exception ignore) {}
        if (!Files.isDirectory(filesDir)) {
            log.warn("Каталог tdlib/files не найден — очистка пропущена");
            return;
        }

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(filesDir)) {
            for (Path sub : ds) {
                if (Files.isDirectory(sub)) {
                    deleteDirectoryContents(sub);
                    log.info("Очищено содержимое {}", sub);
                } else {
                    try { Files.deleteIfExists(sub); }
                    catch (Exception ex) { log.warn("Не удалось удалить файл {}: {}", sub, ex.getMessage()); }
                }
            }
        } catch (IOException e) {
            log.error("Ошибка очистки tdlib/files/*: {}", e.getMessage(), e);
        }
    }

    public final class DbSession implements AutoCloseable {
        private static final int BATCH_LIMIT = 1000;

        private final long chatId;
        private final Connection c;

        private final PreparedStatement insMsg;
        private final PreparedStatement insPhoto;
        private final PreparedStatement insVideo;
        private final PreparedStatement insAudio;
        private final PreparedStatement insDoc;
        private final PreparedStatement insLink;
        private final PreparedStatement selMeta;
        private final PreparedStatement upsertMeta;

        private int pendingOps = 0;

        private DbSession(long chatId, Connection c) throws SQLException {
            this.chatId = chatId;
            this.c = c;

            insMsg   = c.prepareStatement("INSERT OR IGNORE INTO messages(message_id,date,sender_id,reply_to,text) VALUES(?,?,?,?,?)");
            insPhoto = c.prepareStatement("INSERT OR IGNORE INTO photos(message_id,file_id,remote_id,width,height,caption,file_path) VALUES(?,?,?,?,?,?,?)");
            insVideo = c.prepareStatement("INSERT OR IGNORE INTO videos(message_id,file_id,remote_id,duration,width,height,caption,file_path) VALUES(?,?,?,?,?,?,?,?)");
            insAudio = c.prepareStatement("INSERT OR IGNORE INTO audio(message_id,file_id,remote_id,duration,mime,file_path) VALUES(?,?,?,?,?,?)");
            insDoc   = c.prepareStatement("INSERT OR IGNORE INTO documents(message_id,file_id,remote_id,file_name,mime_type,file_path) VALUES(?,?,?,?,?,?)");
            insLink  = c.prepareStatement("INSERT OR IGNORE INTO links(message_id,url,context) VALUES(?,?,?)");

            selMeta    = c.prepareStatement("SELECT value FROM " + q("metadata") + " WHERE key=?");
            upsertMeta = c.prepareStatement("INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)");
        }

        private void bumpAndMaybeCommit() throws SQLException {
            if (++pendingOps >= BATCH_LIMIT) { c.commit(); pendingOps = 0; }
        }

        public void commit() throws SQLException {
            c.commit();
            pendingOps = 0;
        }

        // чтение метаданных
        public String loadMetadata(String key) {
            try {
                selMeta.clearParameters();
                selMeta.setString(1, key);
                try (ResultSet rs = selMeta.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException e) {
                log.warn("Ошибка чтения метаданных '{}': {}", key, e.getMessage());
            }
            return null;
        }

        // запись/обновление метаданных
        public void saveMetadata(String key, String value) {
            try {
                upsertMeta.clearParameters();
                upsertMeta.setString(1, key);
                upsertMeta.setString(2, value);
                upsertMeta.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка записи метаданных '{}': {}", key, e.getMessage(), e);
            }
        }

        // сохранение сущностей
        public void saveMessage(long messageId, long date, String senderId, Long replyTo, String text) {
            try {
                insMsg.clearParameters();
                insMsg.setLong(1, messageId);
                insMsg.setLong(2, date);
                insMsg.setString(3, senderId);
                if (replyTo == null) insMsg.setNull(4, Types.BIGINT); else insMsg.setLong(4, replyTo);
                insMsg.setString(5, text);
                insMsg.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка сохранения сообщения: {}", e.getMessage(), e);
            }
        }

        public void savePhoto(long messageId, Integer fileId, String remoteId,
                              Integer w, Integer h, String caption, String filePath) {
            try {
                insPhoto.clearParameters();
                insPhoto.setLong(1, messageId);
                if (fileId == null) insPhoto.setNull(2, Types.INTEGER); else insPhoto.setInt(2, fileId);
                insPhoto.setString(3, remoteId);
                if (w == null) insPhoto.setNull(4, Types.INTEGER); else insPhoto.setInt(4, w);
                if (h == null) insPhoto.setNull(5, Types.INTEGER); else insPhoto.setInt(5, h);
                insPhoto.setString(6, caption);
                insPhoto.setString(7, filePath);
                insPhoto.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка сохранения фото: {}", e.getMessage(), e);
            }
        }

        public void saveVideo(long messageId, Integer fileId, String remoteId,
                              Integer duration, Integer w, Integer h, String caption, String filePath) {
            try {
                insVideo.clearParameters();
                insVideo.setLong(1, messageId);
                if (fileId == null) insVideo.setNull(2, Types.INTEGER); else insVideo.setInt(2, fileId);
                insVideo.setString(3, remoteId);
                if (duration == null) insVideo.setNull(4, Types.INTEGER); else insVideo.setInt(4, duration);
                if (w == null) insVideo.setNull(5, Types.INTEGER); else insVideo.setInt(5, w);
                if (h == null) insVideo.setNull(6, Types.INTEGER); else insVideo.setInt(6, h);
                insVideo.setString(7, caption);
                insVideo.setString(8, filePath);
                insVideo.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка сохранения видео: {}", e.getMessage(), e);
            }
        }

        public void saveAudio(long messageId, Integer fileId, String remoteId,
                              Integer duration, String mime, String filePath) {
            try {
                insAudio.clearParameters();
                insAudio.setLong(1, messageId);
                if (fileId == null) insAudio.setNull(2, Types.INTEGER); else insAudio.setInt(2, fileId);
                insAudio.setString(3, remoteId);
                if (duration == null) insAudio.setNull(4, Types.INTEGER); else insAudio.setInt(4, duration);
                insAudio.setString(5, mime);
                insAudio.setString(6, filePath);
                insAudio.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка сохранения аудио: {}", e.getMessage(), e);
            }
        }

        public void saveDocument(long messageId, Integer fileId, String remoteId,
                                 String fileName, String mimeType, String filePath) {
            try {
                insDoc.clearParameters();
                insDoc.setLong(1, messageId);
                if (fileId == null) insDoc.setNull(2, Types.INTEGER); else insDoc.setInt(2, fileId);
                insDoc.setString(3, remoteId);
                insDoc.setString(4, fileName);
                insDoc.setString(5, mimeType);
                insDoc.setString(6, filePath);
                insDoc.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка сохранения документа: {}", e.getMessage(), e);
            }
        }

        public void saveLink(long messageId, String url, String context) {
            try {
                insLink.clearParameters();
                insLink.setLong(1, messageId);
                insLink.setString(2, url);
                insLink.setString(3, context);
                insLink.executeUpdate();
                bumpAndMaybeCommit();
            } catch (SQLException e) {
                log.error("Ошибка сохранения ссылки: {}", e.getMessage(), e);
            }
        }

        @Override
        public void close() {
            try { commit(); }             catch (Exception ignore) {}
            try { insMsg.close(); }       catch (Exception ignore) {}
            try { insPhoto.close(); }     catch (Exception ignore) {}
            try { insVideo.close(); }     catch (Exception ignore) {}
            try { insAudio.close(); }     catch (Exception ignore) {}
            try { insDoc.close(); }       catch (Exception ignore) {}
            try { insLink.close(); }      catch (Exception ignore) {}
            try { selMeta.close(); }      catch (Exception ignore) {}
            try { upsertMeta.close(); }   catch (Exception ignore) {}
            try { c.close(); }            catch (Exception ignore) {}
        }
    }

    // удаляет дубли (остаётся запись с MIN(rowid))
    private void dedupTable(Connection c, String table, String keyExpr) {
        String sql = "DELETE FROM " + q(table) + " WHERE rowid NOT IN (" +
                "SELECT MIN(rowid) FROM " + q(table) + " GROUP BY " + keyExpr + ")";
        try (Statement s = c.createStatement()) {
            int removed = s.executeUpdate(sql);
            if (removed > 0) log.info("Дедупликация {}: удалено {} дублей по ({})", table, removed, keyExpr);
        } catch (SQLException e) {
            log.warn("Ошибка дедупликации {}: {}", table, e.getMessage());
        }
    }

    // возвращает MAX(message_id) из таблицы
    private long maxOf(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(message_id), 0) FROM " + q(table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    // удаляет *.db и побочные -wal/-shm
    private void deleteDbWithSidecars(Path dbFile) {
        try { Files.deleteIfExists(dbFile); }                                                catch (IOException ignore) {}
        try { Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal")); }  catch (IOException ignore) {}
        try { Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm")); }  catch (IOException ignore) {}
    }

    // очищает содержимое каталога, но не сам корень
    private void deleteDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (!dir.equals(d)) Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // имя чата для человекочитабельных файлов
    private String chatName(long chatId) {
        try {
            String t = chatResolver.getChatTitle(chatId);
            if (t != null && !t.trim().isEmpty()) return t.trim();
        } catch (Exception ignore) {}
        return "chat_" + Math.abs(chatId);
    }

    // безопасное имя файла
    private String safe(String name, long chatId) {
        if (name == null || name.isBlank()) return "unknown_chat";
        String s = INVALID.matcher(name).replaceAll("_").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) s = "chat_" + Math.abs(chatId);
        if (s.length() > 100) s = s.substring(0, 100);
        return s;
    }

    // путь к файлу БД
    private Path dumpDbPath(long chatId) {
        String fn = safe("DUMP " + chatName(chatId), chatId) + ".db";
        return dbDir.resolve(fn);
    }

    // открывает соединение SQLite чата
    private Connection openDump(long chatId) throws SQLException {
        try { Files.createDirectories(dbDir); } catch (Exception ignore) {}
        return DriverManager.getConnection("jdbc:sqlite:" + dumpDbPath(chatId));
    }

    // SQL-квотирование идентификатора
    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}
