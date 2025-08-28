package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.*;

/**
 * Менеджер БД: создаёт/мигрирует таблицы и сохраняет данные.
 * ВАЖНО: имена таблиц и индексов ВСЕГДА экранируем двойными кавычками,
 * чтобы минус в chat_id не ломал SQL.
 */
@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private final String url = "jdbc:sqlite:tdlib.db";

    /** Экранируем идентификатор для SQLite (двойные кавычки). */
    private static String qIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /** Полное имя таблицы вида chat_<id>_<suffix> в кавычках. */
    private static String tbl(long chatId, String suffix) {
        return qIdent("chat_" + chatId + "_" + suffix);
    }

    public void prepareSchema(long chatId) {
        final String tMessages = tbl(chatId, "messages");
        final String tPhotos   = tbl(chatId, "photos");
        final String tVideos   = tbl(chatId, "videos");
        final String tAudio    = tbl(chatId, "audio");
        final String tLinks    = tbl(chatId, "links");
        final String iLinks    = qIdent("chat_" + chatId + "_links_idx");

        final String createMessages = "CREATE TABLE IF NOT EXISTS " + tMessages + " (" +
                "id INTEGER PRIMARY KEY," +
                "date INTEGER," +
                "sender_id TEXT," +
                "reply_to INTEGER," +
                "text TEXT" +
                ")";
        final String createPhotos = "CREATE TABLE IF NOT EXISTS " + tPhotos + " (" +
                "message_id INTEGER," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT," +
                "PRIMARY KEY (message_id)" +
                ")";
        final String createVideos = "CREATE TABLE IF NOT EXISTS " + tVideos + " (" +
                "message_id INTEGER," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT," +
                "PRIMARY KEY (message_id)" +
                ")";
        final String createAudio = "CREATE TABLE IF NOT EXISTS " + tAudio + " (" +
                "message_id INTEGER," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "mime_type TEXT," +
                "file_path TEXT," +
                "PRIMARY KEY (message_id)" +
                ")";
        final String createLinks = "CREATE TABLE IF NOT EXISTS " + tLinks + " (" +
                "message_id INTEGER," +
                "url TEXT," +
                "context TEXT" +
                ")";
        final String idxLinks = "CREATE INDEX IF NOT EXISTS " + iLinks + " ON " + tLinks + "(url)";

        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {
            s.execute(createMessages);
            s.execute(createPhotos);
            s.execute(createVideos);
            s.execute(createAudio);
            s.execute(createLinks);
            s.execute(idxLinks);

            // Миграция: на всякий случай добавим file_path, если нет
            addColumnIfMissing(c, tPhotos, "file_path", "TEXT");
            addColumnIfMissing(c, tVideos, "file_path", "TEXT");
            addColumnIfMissing(c, tAudio,  "file_path", "TEXT");

            log.info("БД: схема для чата {} готова", chatId);
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection c, String quotedTable, String col, String type) {
        // quotedTable уже в кавычках
        String pragma = "PRAGMA table_info(" + quotedTable + ")";
        try (PreparedStatement ps = c.prepareStatement(pragma)) {
            try (ResultSet rs = ps.executeQuery()) {
                boolean exists = false;
                while (rs.next()) {
                    if (col.equalsIgnoreCase(rs.getString("name"))) { exists = true; break; }
                }
                if (!exists) {
                    String alter = "ALTER TABLE " + quotedTable + " ADD COLUMN " + col + " " + type;
                    try (Statement s = c.createStatement()) {
                        s.execute(alter);
                        log.info("БД: для {} добавлен столбец {}", quotedTable, col);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить/добавить столбец {} в {}: {}", col, quotedTable, e.getMessage());
        }
    }

    /** Максимальный message_id, уже сохранённый в БД по чату (смотрим во всех таблицах). */
    public long getLastSavedIdForChat(long chatId) {
        long max = 0;
        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {

            max = Math.max(max, scalarMaxLong(s, "SELECT COALESCE(MAX(id),0) FROM " + tbl(chatId, "messages")));
            max = Math.max(max, scalarMaxLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + tbl(chatId, "photos")));
            max = Math.max(max, scalarMaxLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + tbl(chatId, "videos")));
            max = Math.max(max, scalarMaxLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + tbl(chatId, "audio")));
        } catch (SQLException e) {
            log.debug("БД: getLastSavedIdForChat({}): {}", chatId, e.getMessage());
        }
        return max;
    }

    private long scalarMaxLong(Statement s, String sql) {
        try (ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException ignored) {}
        return 0L;
    }

    public void saveMessage(long chatId, long messageId, long date, String senderId, Long replyTo, String text) {
        final String tMessages = tbl(chatId, "messages");
        final String sql = "INSERT OR IGNORE INTO " + tMessages + "(id, date, sender_id, reply_to, text) VALUES(?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setLong(2, date);
            st.setString(3, senderId);
            if (replyTo == null) st.setNull(4, Types.INTEGER); else st.setLong(4, replyTo);
            st.setString(5, text);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения сообщения {} для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void savePhoto(long chatId, long messageId, Integer fileId, String remoteId, Integer w, Integer h, String caption, String filePath) {
        final String tPhotos = tbl(chatId, "photos");
        final String sql = "INSERT OR REPLACE INTO " + tPhotos + "(message_id, file_id, remote_id, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER); else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (w == null) st.setNull(4, Types.INTEGER); else st.setInt(4, w);
            if (h == null) st.setNull(5, Types.INTEGER); else st.setInt(5, h);
            st.setString(6, caption);
            st.setString(7, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения фото (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveVideo(long chatId, long messageId, Integer fileId, String remoteId, Integer duration, Integer w, Integer h, String caption, String filePath) {
        final String tVideos = tbl(chatId, "videos");
        final String sql = "INSERT OR REPLACE INTO " + tVideos + "(message_id, file_id, remote_id, duration, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER); else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER); else st.setInt(4, duration);
            if (w == null) st.setNull(5, Types.INTEGER); else st.setInt(5, w);
            if (h == null) st.setNull(6, Types.INTEGER); else st.setInt(6, h);
            st.setString(7, caption);
            st.setString(8, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения видео (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveAudio(long chatId, long messageId, Integer fileId, String remoteId, Integer duration, String mime, String filePath) {
        final String tAudio = tbl(chatId, "audio");
        final String sql = "INSERT OR REPLACE INTO " + tAudio + "(message_id, file_id, remote_id, duration, mime_type, file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER); else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER); else st.setInt(4, duration);
            st.setString(5, mime);
            st.setString(6, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения аудио (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    public void saveLink(long chatId, long messageId, String urlStr, String context) {
        final String tLinks = tbl(chatId, "links");
        final String sql = "INSERT INTO " + tLinks + "(message_id, url, context) VALUES(?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setString(2, urlStr);
            st.setString(3, context);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения ссылки (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }
}
