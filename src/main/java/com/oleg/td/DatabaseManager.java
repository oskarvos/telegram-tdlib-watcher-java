package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;

@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    /**
     * каталог с файлами БД чатов: tdlib/db/chat_<absId>.db
     */
    private final Path dbDir = Paths.get("tdlib", "db");

    public DatabaseManager() {
        try {
            Files.createDirectories(dbDir);
        } catch (Exception e) {
            log.warn("БД: не удалось создать каталог {}: {}", dbDir, e.toString());
        }
    }

    private static String qIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private Path dbPath(long chatId) {
        return dbDir.resolve("chat_" + Math.abs(chatId) + ".db");
    }

    private String dbUrl(long chatId) {
        return "jdbc:sqlite:" + dbPath(chatId);
    }

    private Connection open(long chatId) throws SQLException {
        return DriverManager.getConnection(dbUrl(chatId));
    }

    /* ================= schema ================= */

    public void prepareSchema(long chatId) {
        final String tMessages = qIdent("messages");
        final String tPhotos = qIdent("photos");
        final String tVideos = qIdent("videos");
        final String tAudio = qIdent("audio");
        final String tDocuments = qIdent("documents");
        final String tLinks = qIdent("links");
        final String iLinks = qIdent("links_idx");

        final String createMessages = "CREATE TABLE IF NOT EXISTS " + tMessages + " (" +
                "id INTEGER PRIMARY KEY," +
                "date INTEGER," +
                "sender_id TEXT," +
                "reply_to INTEGER," +
                "text TEXT" +
                ")";
        final String createPhotos = "CREATE TABLE IF NOT EXISTS " + tPhotos + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT" +
                ")";
        final String createVideos = "CREATE TABLE IF NOT EXISTS " + tVideos + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT" +
                ")";
        final String createAudio = "CREATE TABLE IF NOT EXISTS " + tAudio + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "mime_type TEXT," +
                "file_path TEXT" +
                ")";
        final String createDocuments = "CREATE TABLE IF NOT EXISTS " + tDocuments + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "file_name TEXT," +
                "mime_type TEXT," +
                "file_size INTEGER," +
                "caption TEXT," +
                "file_path TEXT" +
                ")";
        final String createLinks = "CREATE TABLE IF NOT EXISTS " + tLinks + " (" +
                "message_id INTEGER," +
                "url TEXT," +
                "context TEXT" +
                ")";
        final String idxLinks = "CREATE INDEX IF NOT EXISTS " + iLinks + " ON " + tLinks + "(url)";

        try (Connection c = open(chatId); Statement s = c.createStatement()) {
            s.execute(createMessages);
            s.execute(createPhotos);
            s.execute(createVideos);
            s.execute(createAudio);
            s.execute(createDocuments);
            s.execute(createLinks);
            s.execute(idxLinks);

            addColumnIfMissing(c, "photos", "file_path", "TEXT");
            addColumnIfMissing(c, "videos", "file_path", "TEXT");
            addColumnIfMissing(c, "audio", "file_path", "TEXT");
            addColumnIfMissing(c, "documents", "file_path", "TEXT");

            log.info("БД: [{}] схема готова: {}", chatId, dbPath(chatId));
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection c, String table, String col, String type) {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + qIdent(table) + ")");
             ResultSet rs = ps.executeQuery()) {
            boolean exists = false;
            while (rs.next()) if (col.equalsIgnoreCase(rs.getString("name"))) {
                exists = true;
                break;
            }
            if (!exists) {
                try (Statement s = c.createStatement()) {
                    s.execute("ALTER TABLE " + qIdent(table) + " ADD COLUMN " + col + " " + type);
                    log.info("БД: для {} добавлен столбец {}", table, col);
                }
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить/добавить столбец {} в {}: {}", col, table, e.getMessage());
        }
    }

    /* ============ incremental helpers ============ */

    /**
     * MAX(id) из messages; если строк нет — 0.
     */
    public long getLastSavedMessageId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(id),0) FROM " + qIdent("messages");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * MAX(message_id) по медиа-таблицам (на случай если сообщения не сохраняли).
     */
    public long getMaxMediaId(long chatId) {
        long max = 0;
        try (Connection c = open(chatId); Statement s = c.createStatement()) {
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("photos")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("videos")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("audio")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("documents")));
        } catch (SQLException ignored) {
        }
        return max;
    }

    private long scalarLong(Statement s, String sql) {
        try (ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException ignored) {
        }
        return 0L;
    }


    public void saveMessage(long chatId, long messageId, long date, String senderId, Long replyTo, String text) {
        final String sql = "INSERT OR IGNORE INTO " + qIdent("messages") +
                "(id, date, sender_id, reply_to, text) VALUES(?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setLong(2, date);
            st.setString(3, senderId);
            if (replyTo == null) st.setNull(4, Types.INTEGER);
            else st.setLong(4, replyTo);
            st.setString(5, text);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения сообщения {} для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void savePhoto(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer w, Integer h, String caption, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("photos") +
                "(message_id, file_id, remote_id, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (w == null) st.setNull(4, Types.INTEGER);
            else st.setInt(4, w);
            if (h == null) st.setNull(5, Types.INTEGER);
            else st.setInt(5, h);
            st.setString(6, caption);
            st.setString(7, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения фото (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveVideo(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer duration, Integer w, Integer h, String caption, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("videos") +
                "(message_id, file_id, remote_id, duration, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER);
            else st.setInt(4, duration);
            if (w == null) st.setNull(5, Types.INTEGER);
            else st.setInt(5, w);
            if (h == null) st.setNull(6, Types.INTEGER);
            else st.setInt(6, h);
            st.setString(7, caption);
            st.setString(8, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения видео (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveAudio(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer duration, String mime, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("audio") +
                "(message_id, file_id, remote_id, duration, mime_type, file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER);
            else st.setInt(4, duration);
            st.setString(5, mime);
            st.setString(6, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения аудио (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveDocument(long chatId, long messageId, Integer fileId, String remoteId,
                             String fileName, String mimeType, Integer fileSize,
                             String caption, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("documents") +
                "(message_id, file_id, remote_id, file_name, mime_type, file_size, caption, file_path) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            st.setString(4, fileName);
            st.setString(5, mimeType);
            if (fileSize == null) st.setNull(6, Types.INTEGER);
            else st.setInt(6, fileSize);
            st.setString(7, caption);
            st.setString(8, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения документа (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveLink(long chatId, long messageId, String urlStr, String context) {
        final String sql = "INSERT INTO " + qIdent("links") + "(message_id, url, context) VALUES(?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setString(2, urlStr);
            st.setString(3, context);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения ссылки (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }
}