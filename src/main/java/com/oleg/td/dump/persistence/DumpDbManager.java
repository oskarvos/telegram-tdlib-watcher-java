package com.oleg.td.dump.persistence;

import com.oleg.td.common.DbUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class DumpDbManager {
    private static final Logger log = LoggerFactory.getLogger(DumpDbManager.class);
    private final DbUtils dbUtils;

    public DumpDbManager(DbUtils dbUtils) {
        this.dbUtils = dbUtils;
    }

    private Path dumpDbPath(long chatId) {
        String chatName = dbUtils.getChatNameForDatabase(chatId);
        String safe = dbUtils.sanitizeFileName("DUMP " + chatName, chatId) + ".db";
        return dbUtils.getDbDir().resolve(safe);
    }

    private String dbUrl(long chatId) {
        return "jdbc:sqlite:" + dumpDbPath(chatId);
    }

    private Connection open(long chatId) throws SQLException {
        return DriverManager.getConnection(dbUrl(chatId));
    }

    public void prepareSchema(long chatId) {
        final String tMessages = dbUtils.qIdent("messages");
        final String tPhotos = dbUtils.qIdent("photos");
        final String tVideos = dbUtils.qIdent("videos");
        final String tAudio = dbUtils.qIdent("audio");
        final String tDocuments = dbUtils.qIdent("documents");
        final String tLinks = dbUtils.qIdent("links");
        final String tMetadata = dbUtils.qIdent("metadata");
        final String tCheckpoints = dbUtils.qIdent("checkpoints");
        final String iLinks = dbUtils.qIdent("links_idx");

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
                "file_path TEXT" +
                ")";
        final String createLinks = "CREATE TABLE IF NOT EXISTS " + tLinks + " (" +
                "message_id INTEGER," +
                "url TEXT," +
                "context TEXT" +
                ")";
        final String createMetadata = "CREATE TABLE IF NOT EXISTS " + tMetadata + " (" +
                "key TEXT PRIMARY KEY," +
                "value TEXT" +
                ")";
        final String createCheckpoints = "CREATE TABLE IF NOT EXISTS " + tCheckpoints + " (" +
                "chat_id INTEGER PRIMARY KEY," +
                "last_message_id INTEGER NOT NULL DEFAULT 0," +
                "last_updated TEXT" +
                ")";
        final String idxLinks = "CREATE INDEX IF NOT EXISTS " + iLinks + " ON " + tLinks + "(url)";

        try (Connection c = open(chatId); Statement s = c.createStatement()) {
            s.execute(createMessages);
            s.execute(createPhotos);
            s.execute(createVideos);
            s.execute(createAudio);
            s.execute(createDocuments);
            s.execute(createLinks);
            s.execute(createMetadata);
            s.execute(createCheckpoints);
            s.execute(idxLinks);

            addColumnIfMissing(c, "photos", "file_path", "TEXT");
            addColumnIfMissing(c, "videos", "file_path", "TEXT");
            addColumnIfMissing(c, "audio", "file_path", "TEXT");
            addColumnIfMissing(c, "documents", "file_path", "TEXT");

            log.info("БД 'DUMP {}' схема готова", dbUtils.getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы DUMP для '{}': {}", dbUtils.getChatNameForDatabase(chatId), e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection c, String table, String col, String type) {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + dbUtils.qIdent(table) + ")");
             ResultSet rs = ps.executeQuery()) {
            boolean exists = false;
            while (rs.next()) {
                if (col.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                try (Statement s = c.createStatement()) {
                    s.execute("ALTER TABLE " + dbUtils.qIdent(table) + " ADD COLUMN " + col + " " + type);
                    log.info("БД: для {} добавлен столбец {}", table, col);
                }
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить/добавить столбец {} в {}: {}", col, table, e.getMessage());
        }
    }

    public long getLastSavedMessageId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(id),0) FROM " + dbUtils.qIdent("messages");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    public long getMaxMediaId(long chatId) {
        long max = 0;
        try (Connection c = open(chatId); Statement s = c.createStatement()) {
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + dbUtils.qIdent("photos")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + dbUtils.qIdent("videos")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + dbUtils.qIdent("audio")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + dbUtils.qIdent("documents")));
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
        final String sql = "INSERT OR IGNORE INTO " + dbUtils.qIdent("messages") +
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
        final String sql = "INSERT OR REPLACE INTO " + dbUtils.qIdent("photos") +
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
        final String sql = "INSERT OR REPLACE INTO " + dbUtils.qIdent("videos") +
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
        final String sql = "INSERT OR REPLACE INTO " + dbUtils.qIdent("audio") +
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
                             String fileName, String mimeType, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + dbUtils.qIdent("documents") +
                "(message_id, file_id, remote_id, file_name, mime_type, file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            st.setString(4, fileName);
            st.setString(5, mimeType);
            st.setString(6, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения документа (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveLink(long chatId, long messageId, String urlStr, String context) {
        final String sql = "INSERT INTO " + dbUtils.qIdent("links") + "(message_id, url, context) VALUES(?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setString(2, urlStr);
            st.setString(3, context);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения ссылки (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveMetadata(long chatId, String key, String value) {
        final String sql = "INSERT OR REPLACE INTO " + dbUtils.qIdent("metadata") + "(key, value) VALUES(?, ?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, key);
            st.setString(2, value);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения метаданных для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    public String loadMetadata(long chatId, String key) {
        final String sql = "SELECT value FROM " + dbUtils.qIdent("metadata") + " WHERE key = ?";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            log.error("БД: ошибка загрузки метаданных для чата {}: {}", chatId, e.getMessage(), e);
        }
        return null;
    }

    public void saveCheckpoint(long chatId, long lastMessageId) {
        final String sql = "REPLACE INTO " + dbUtils.qIdent("checkpoints") + "(chat_id, last_message_id, last_updated) VALUES(?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, chatId);
            st.setLong(2, lastMessageId);
            st.setString(3, LocalDateTime.now().toString());
            st.executeUpdate();
            log.debug("БД: сохранена контрольная точка для чата {}: last_message_id={}", chatId, lastMessageId);
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения контрольной точки для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    public long loadCheckpoint(long chatId) {
        final String sql = "SELECT last_message_id FROM " + dbUtils.qIdent("checkpoints") + " WHERE chat_id = ?";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, chatId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getLong("last_message_id");
            }
        } catch (SQLException e) {
            log.error("БД: ошибка загрузки контрольной точки для чата {}: {}", chatId, e.getMessage(), e);
        }
        return 0L;
    }

    public void resetSearchCheckpoint(long chatId) {
        try (Connection c = open(chatId)) {
            if (tableExists(c, "metadata")) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM " + dbUtils.qIdent("metadata") + " WHERE key = ?")) {
                    ps.setString(1, "search_last_message_id");
                    ps.executeUpdate();
                }
            }
            log.info("БД(DUMP {}): сброшен чекпоинт поиска search_last_message_id", dbUtils.getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД(DUMP): ошибка сброса чекпоинта поиска для {}: {}", chatId, e.getMessage(), e);
        }
    }

    public void resetAllSearchCheckpoints() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbUtils.getDbDir(), "DUMP *.db")) {
            for (Path p : ds) {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    if (tableExists(c, "metadata")) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM " + dbUtils.qIdent("metadata") + " WHERE key = ?")) {
                            ps.setString(1, "search_last_message_id");
                            ps.executeUpdate();
                        }
                        log.info("DUMP-БД {}: сброшен search_last_message_id", p.getFileName());
                    }
                } catch (SQLException e) {
                    log.error("БД(DUMP): ошибка сброса чекпоинта в {}: {}", p.getFileName(), e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("БД: ошибка перебора каталога {}: {}", dbUtils.getDbDir(), e.getMessage(), e);
        }
    }

    private boolean tableExists(Connection c, String table) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить наличие таблицы {}: {}", table, e.getMessage());
            return false;
        }
    }
}