package com.oleg.td.monitor.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

/**
 * Менеджер SQLite БД мониторинга.
 * Одна БД на чат, хранение метаданных и результатов.
 */
@Component
public class MonitorDbManager {

    private static final Logger log = LoggerFactory.getLogger(MonitorDbManager.class);

    private final Path dbDir = Paths.get("tdlib", "db"); // директория БД
    private final ChatResolver chatResolver;             // для получения названий чатов

    // недопустимые символы для имени файла
    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|]");

    public MonitorDbManager(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
        try {
            Files.createDirectories(dbDir);
        } catch (Exception ignored) {
        }
    }

    // экранирование идентификатора SQL
    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    // получение «человеческого» имени чата
    private String chatName(long chatId) {
        try {
            String t = chatResolver.getChatTitle(chatId);
            if (t != null && !t.trim().isEmpty()) return t.trim();
        } catch (Exception ignored) {
        }
        return "chat_" + Math.abs(chatId);
    }

    // безопасное имя для файла БД
    private String safe(String name, long chatId) {
        if (name == null || name.isBlank()) return "unknown_chat";
        String s = INVALID.matcher(name).replaceAll("_").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) s = "chat_" + Math.abs(chatId);
        if (s.length() > 100) s = s.substring(0, 100);
        return s;
    }

    // путь к файлу БД (на чат)
    private Path monitorDbPath(long chatId) {
        String fn = safe("MONITOR " + chatName(chatId), chatId) + ".db";
        return dbDir.resolve(fn);
    }

    // открыть соединение к БД чата
    private Connection openMonitor(long chatId) throws SQLException {
        Path p = monitorDbPath(chatId);
        try {
            Files.createDirectories(p.getParent());
        } catch (Exception e) { // крит. проблема с ФС
            log.error("Не удалось создать директорию для БД {}: {}", p.toAbsolutePath(), e.getMessage(), e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + p.toAbsolutePath());
    }

    /** Убедиться, что таблица metadata существует. */
    public void ensureMonitorMetadata(long chatId) {
        try (Connection c = openMonitor(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e) {
            log.error("MONITOR {}: не удалось создать metadata: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    /** Прочитать чекпоинт последнего сообщения. */
    public String loadMonitorCheckpoint(long chatId) {
        final String sql = "SELECT value FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openMonitor(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "monitor_last_message_id");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            log.warn("Ошибка чтения чекпоинта: {}", e.getMessage());
        }
        return null;
    }

    /** Сохранить чекпоинт последнего сообщения. */
    public void saveMonitorCheckpoint(long chatId, long messageId) {
        final String sql = "INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)";
        try (Connection c = openMonitor(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "monitor_last_message_id");
            ps.setString(2, Long.toString(messageId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка сохранения чекпоинта: {}", e.getMessage(), e);
        }
    }

    /** Сбросить чекпоинт. */
    public void resetMonitorCheckpoint(long chatId) {
        final String sql = "DELETE FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openMonitor(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "monitor_last_message_id");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Ошибка сброса чекпоинта: {}", e.getMessage(), e);
        }
    }

    /** Создать схему таблиц результатов мониторинга. */
    public void prepareMonitorSchema(long chatId) {
        final String t = q("monitor_results");
        final String create = "CREATE TABLE IF NOT EXISTS " + t + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "message_id INTEGER," +
                "message_date TEXT," +
                "keyword TEXT," +
                "message_text TEXT," +
                "sender_id TEXT," +
                "sender_name TEXT," +
                "found_date TEXT" +
                ")";
        final String idx1 = "CREATE INDEX IF NOT EXISTS monitor_keyword_idx ON " + t + "(keyword)";
        final String idx2 = "CREATE INDEX IF NOT EXISTS monitor_date_idx ON " + t + "(message_date)";
        try (Connection c = openMonitor(chatId); Statement s = c.createStatement()) {
            s.execute(create);
            s.execute(idx1);
            s.execute(idx2);
        } catch (SQLException e) {
            log.error("MONITOR {}: ошибка создания схемы: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    /** Сохранить найденное попадание. */
    public void saveMonitorHit(long chatId, long messageId, LocalDateTime messageDate,
                               String keyword, String messageText, String senderId, String senderName) {
        final String sql = "INSERT INTO " + q("monitor_results") +
                "(message_id,message_date,keyword,message_text,sender_id,sender_name,found_date) " +
                "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openMonitor(chatId); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setString(2, messageDate.toString());
            ps.setString(3, keyword);
            ps.setString(4, messageText);
            ps.setString(5, senderId);
            ps.setString(6, senderName);
            ps.setString(7, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("MONITOR: ошибка сохранения попадания: {}", e.getMessage(), e);
        }
    }

    /** Удалить все БД мониторинга (включая -wal/-shm). */
    public void clearMonitorChatDatabases() {
        try {
            Files.createDirectories(dbDir);
        } catch (Exception ignore) {
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "MONITOR *.db")) {
            for (Path p : ds) {
                deleteDbWithSidecars(p); // удаляем файл + -wal/-shm
                log.info("Удалён файл БД мониторинга: {}", p.getFileName());
            }
        } catch (Exception e) {
            log.error("Ошибка перечисления файлов БД мониторинга: {}", e.getMessage(), e);
        }
    }

    // удалить БД и соседние файлы
    private void deleteDbWithSidecars(Path dbFile) {
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException ignore) {}
        try {
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName().toString() + "-wal"));
        } catch (IOException ignore) {}
        try {
            Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName().toString() + "-shm"));
        } catch (IOException ignore) {}
    }

    /** Получить результаты по чату. */
    public java.util.List<com.oleg.td.monitor.model.MonitorHit> getMonitorResults(long chatId, int limit, int offset) {
        final String sql = "SELECT rowid AS id, message_id, message_date, keyword, message_text, " +
                "sender_id, sender_name, found_date FROM " + q("monitor_results") +
                " ORDER BY found_date DESC LIMIT ? OFFSET ?";
        java.util.List<com.oleg.td.monitor.model.MonitorHit> list = new java.util.ArrayList<>();
        try (Connection c = openMonitor(chatId); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    com.oleg.td.monitor.model.MonitorHit h = new com.oleg.td.monitor.model.MonitorHit();
                    h.setId(rs.getLong("id"));
                    h.setChatId(chatId);
                    h.setChatTitle(chatName(chatId));
                    h.setMessageId(rs.getLong("message_id"));
                    h.setMessageDate(java.time.LocalDateTime.parse(rs.getString("message_date")));
                    h.setKeyword(rs.getString("keyword"));
                    h.setMessageText(rs.getString("message_text"));
                    h.setSenderId(rs.getString("sender_id"));
                    h.setSenderName(rs.getString("sender_name"));
                    h.setFoundDate(java.time.LocalDateTime.parse(rs.getString("found_date")));
                    list.add(h);
                }
            }
        } catch (SQLException e) {
            log.error("MONITOR: ошибка чтения результатов: {}", e.getMessage(), e);
        }
        return list;
    }
}
