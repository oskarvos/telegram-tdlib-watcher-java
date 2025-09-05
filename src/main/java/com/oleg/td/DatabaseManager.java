package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
            if (e.getMessage().contains("no such table")) {
                // Таблица не существует, возвращаем 0
                return 0L;
            }
            log.warn("БД: ошибка получения последнего ID сообщения для чата {}: {}", chatId, e.getMessage());
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

    /* ============ upserts ============ */

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
            if (e.getMessage().contains("no such table")) {
                // Если таблицы не существует, создаем схему и пробуем снова
                log.warn("Таблица messages не существует для чата {}, создаем схему...", chatId);
                prepareSchema(chatId);
                saveMessage(chatId, messageId, date, senderId, replyTo, text); // Рекурсивный вызов
            } else {
                log.error("БД: ошибка сохранения сообщения {} для чата {}: {}", messageId, chatId, e.getMessage(), e);
            }
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
                             String fileName, String mimeType, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("documents") +
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

    public void prepareMonitorSchema() {
        final String tMonitorResults = qIdent("monitor_results");

        final String createMonitorResults = "CREATE TABLE IF NOT EXISTS " + tMonitorResults + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "chat_id INTEGER," +
                "chat_title TEXT," +
                "message_id INTEGER," +
                "message_date TEXT," + // ISO format
                "keyword TEXT," +
                "message_text TEXT," +
                "sender_id TEXT," +
                "sender_name TEXT," +
                "found_date TEXT," + // When we found it
                "UNIQUE(chat_id, message_id, keyword)" +
                ")";

        final String idxMonitorChat = "CREATE INDEX IF NOT EXISTS monitor_chat_idx ON " + tMonitorResults + "(chat_id)";
        final String idxMonitorKeyword = "CREATE INDEX IF NOT EXISTS monitor_keyword_idx ON " + tMonitorResults + "(keyword)";
        final String idxMonitorDate = "CREATE INDEX IF NOT EXISTS monitor_date_idx ON " + tMonitorResults + "(message_date)";

        try (Connection c = open(0); Statement s = c.createStatement()) { // Use chat_id 0 for monitor DB
            s.execute(createMonitorResults);
            s.execute(idxMonitorChat);
            s.execute(idxMonitorKeyword);
            s.execute(idxMonitorDate);
            log.info("БД: схема мониторинга готова");
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы мониторинга: {}", e.getMessage(), e);
        }
    }

    public void saveMonitorResult(MonitorResult result) {
        final String sql = "INSERT OR IGNORE INTO " + qIdent("monitor_results") +
                "(chat_id, chat_title, message_id, message_date, keyword, message_text, sender_id, sender_name, found_date) " +
                "VALUES(?,?,?,?,?,?,?,?,?)";

        try (Connection c = open(0); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, result.getChatId());
            st.setString(2, result.getChatTitle());
            st.setLong(3, result.getMessageId());
            st.setString(4, result.getMessageDate().toString());
            st.setString(5, result.getKeyword());
            st.setString(6, result.getMessageText());
            st.setString(7, result.getSenderId());
            st.setString(8, result.getSenderName());
            st.setString(9, LocalDateTime.now().toString());

            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения результата мониторинга: {}", e.getMessage(), e);
        }
    }

    public List<MonitorResult> getMonitorResultsByChatAndMessage(long chatId, long messageId, String keyword) {
        List<MonitorResult> results = new ArrayList<>();
        String sql = "SELECT * FROM " + qIdent("monitor_results") +
                " WHERE chat_id = ? AND message_id = ? AND keyword = ?";

        try (Connection c = open(0); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, chatId);
            st.setLong(2, messageId);
            st.setString(3, keyword);

            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    MonitorResult result = new MonitorResult();
                    result.setChatId(rs.getLong("chat_id"));
                    result.setChatTitle(rs.getString("chat_title"));
                    result.setMessageId(rs.getLong("message_id"));
                    result.setMessageDate(LocalDateTime.parse(rs.getString("message_date")));
                    result.setKeyword(rs.getString("keyword"));
                    result.setMessageText(rs.getString("message_text"));
                    result.setSenderId(rs.getString("sender_id"));
                    result.setSenderName(rs.getString("sender_name"));

                    results.add(result);
                }
            }
        } catch (SQLException e) {
            log.error("БД: ошибка проверки существующего результата: {}", e.getMessage(), e);
        }

        return results;
    }

    public List<MonitorResult> getMonitorResults(String keywordFilter, LocalDateTime dateFrom, LocalDateTime dateTo) {
        List<MonitorResult> results = new ArrayList<>();
        String sql = "SELECT * FROM " + qIdent("monitor_results") + " WHERE 1=1";
        List<Object> params = new ArrayList<>();

        if (keywordFilter != null && !keywordFilter.isEmpty()) {
            sql += " AND keyword LIKE ?";
            params.add("%" + keywordFilter + "%");
        }
        if (dateFrom != null) {
            sql += " AND message_date >= ?";
            params.add(dateFrom.toString());
        }
        if (dateTo != null) {
            sql += " AND message_date <= ?";
            params.add(dateTo.toString());
        }
        sql += " ORDER BY message_date DESC";

        try (Connection c = open(0); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                st.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    MonitorResult result = new MonitorResult();
                    result.setChatId(rs.getLong("chat_id"));
                    result.setChatTitle(rs.getString("chat_title"));
                    result.setMessageId(rs.getLong("message_id"));
                    result.setMessageDate(LocalDateTime.parse(rs.getString("message_date")));
                    result.setKeyword(rs.getString("keyword"));
                    result.setMessageText(rs.getString("message_text"));
                    result.setSenderId(rs.getString("sender_id"));
                    result.setSenderName(rs.getString("sender_name"));

                    results.add(result);
                }
            }
        } catch (SQLException e) {
            log.error("БД: ошибка получения результатов мониторинга: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * Получить сообщения после указанного ID
     */
    public List<MessageInfo> getMessagesAfterId(long chatId, long lastMessageId) {
        List<MessageInfo> messages = new ArrayList<>();
        final String sql = "SELECT id, date, sender_id, text FROM " + qIdent("messages") +
                " WHERE id > ? ORDER BY id ASC";

        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, lastMessageId);

            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    MessageInfo message = new MessageInfo();
                    message.setId(rs.getLong("id"));
                    message.setDate(LocalDateTime.ofEpochSecond(rs.getLong("date"), 0, ZoneOffset.UTC));
                    message.setSenderId(rs.getString("sender_id"));
                    message.setText(rs.getString("text"));

                    messages.add(message);
                }
            }
        } catch (SQLException e) {
            log.error("БД: ошибка получения сообщений после ID {}: {}", lastMessageId, e.getMessage());
        }

        return messages;
    }

    /**
     * Проверить существование сообщения в базе
     */
    public boolean messageExists(long chatId, long messageId) {
        final String sql = "SELECT COUNT(*) FROM " + qIdent("messages") + " WHERE id = ?";

        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);

            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("БД: ошибка проверки существования сообщения {}: {}", messageId, e.getMessage());
            return false;
        }
    }
}