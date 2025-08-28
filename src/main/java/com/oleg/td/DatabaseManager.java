package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.*;

/**
 * Менеджер БД: создаёт/мигрирует таблицы и сохраняет данные.
 * Теперь для медиа-таблиц добавлен столбец file_path — локальный путь к скачанному файлу.
 */
@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private final String url = "jdbc:sqlite:tdlib.db";

    public void prepareSchema(long chatId) {
        final String p = "chat_" + chatId;

        final String createMessages = "CREATE TABLE IF NOT EXISTS " + p + "_messages (" +
                "id INTEGER PRIMARY KEY," +
                "date INTEGER," +
                "sender_id TEXT," +
                "reply_to INTEGER," +
                "text TEXT" +
                ")";
        final String createPhotos = "CREATE TABLE IF NOT EXISTS " + p + "_photos (" +
                "message_id INTEGER," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT," +
                "PRIMARY KEY (message_id)" +
                ")";
        final String createVideos = "CREATE TABLE IF NOT EXISTS " + p + "_videos (" +
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
        final String createAudio = "CREATE TABLE IF NOT EXISTS " + p + "_audio (" +
                "message_id INTEGER," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "mime_type TEXT," +
                "file_path TEXT," +
                "PRIMARY KEY (message_id)" +
                ")";
        final String createLinks = "CREATE TABLE IF NOT EXISTS " + p + "_links (" +
                "message_id INTEGER," +
                "url TEXT," +
                "context TEXT" +
                ")";
        final String idxLinks = "CREATE INDEX IF NOT EXISTS " + p + "_links_idx ON " + p + "_links(url)";

        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {
            s.execute(createMessages);
            s.execute(createPhotos);
            s.execute(createVideos);
            s.execute(createAudio);
            s.execute(createLinks);
            s.execute(idxLinks);

            // Простая миграция: если таблица была создана ранее — пытаемся добавить file_path
            addColumnIfMissing(c, p + "_photos", "file_path", "TEXT");
            addColumnIfMissing(c, p + "_videos", "file_path", "TEXT");
            addColumnIfMissing(c, p + "_audio",  "file_path", "TEXT");

            log.info("БД: схема для чата {} готова", chatId);
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    private void addColumnIfMissing(Connection c, String table, String col, String type) {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + table + ")")) {
            try (ResultSet rs = ps.executeQuery()) {
                boolean exists = false;
                while (rs.next()) {
                    if (col.equalsIgnoreCase(rs.getString("name"))) {
                        exists = true; break;
                    }
                }
                if (!exists) {
                    try (Statement s = c.createStatement()) {
                        s.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
                        log.info("БД: для {} добавлен столбец {}", table, col);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить/добавить столбец {} в {}: {}", col, table, e.getMessage());
        }
    }

    public void saveMessage(long chatId, long messageId, long date, String senderId, Long replyTo, String text) {
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR IGNORE INTO " + p + "_messages(id, date, sender_id, reply_to, text) VALUES(?,?,?,?,?)";
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
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR REPLACE INTO " + p + "_photos(message_id, file_id, remote_id, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?)";
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
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR REPLACE INTO " + p + "_videos(message_id, file_id, remote_id, duration, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?,?)";
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
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR REPLACE INTO " + p + "_audio(message_id, file_id, remote_id, duration, mime_type, file_path) VALUES(?,?,?,?,?,?)";
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
        final String p = "chat_" + chatId;
        final String sql = "INSERT INTO " + p + "_links(message_id, url, context) VALUES(?,?,?)";
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
