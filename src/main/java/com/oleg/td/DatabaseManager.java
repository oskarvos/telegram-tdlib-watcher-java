// ============================================================================
// File: src/main/java/com/oleg/td/DatabaseManager.java
// Назначение: Создание схемы БД и сохранение сущностей чата.
// ============================================================================
package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.*;

/**
 * Менеджер БД (SQLite): создаёт таблицы на чат и сохраняет сообщения/медиа/ссылки.
 */
@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    // Путь к SQLite (файл в корне проекта)
    private final String url = "jdbc:sqlite:tdlib.db";

    /**
     * Создаёт все таблицы для конкретного чата.
     * @param chatId числовой идентификатор чата
     */
    public void prepareFullSchema(long chatId) {
        String p = tablePrefix(chatId);
        try (Connection c = DriverManager.getConnection(url); Statement s = c.createStatement()) {
            // Сообщения (текст и raw JSON)
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "_messages (" +
                    "id INTEGER PRIMARY KEY, date INTEGER, sender TEXT, text TEXT, raw_json TEXT)");
            // Фото
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "_photos (" +
                    "id INTEGER PRIMARY KEY, message_id INTEGER, date INTEGER, sender TEXT, " +
                    "file_id TEXT, unique_id TEXT, remote_id TEXT, width INTEGER, height INTEGER, " +
                    "caption TEXT, raw_json TEXT)");
            // Видео
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "_videos (" +
                    "id INTEGER PRIMARY KEY, message_id INTEGER, date INTEGER, sender TEXT, " +
                    "file_id TEXT, unique_id TEXT, remote_id TEXT, duration INTEGER, file_name TEXT, mime_type TEXT, " +
                    "width INTEGER, height INTEGER, caption TEXT, raw_json TEXT)");
            // Аудио (включая голосовые)
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "_audio (" +
                    "id INTEGER PRIMARY KEY, message_id INTEGER, date INTEGER, sender TEXT, subtype TEXT, " +
                    "file_id TEXT, unique_id TEXT, remote_id TEXT, duration INTEGER, file_name TEXT, mime_type TEXT, " +
                    "caption TEXT, raw_json TEXT)");
            // Ссылки
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + p + "_links (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, message_id INTEGER, url TEXT, text_snippet TEXT, raw_json TEXT, " +
                    "UNIQUE(message_id, url))");
            log.info("Создана/проверена схема БД для чата {}", chatId);
        } catch (SQLException e) {
            log.error("Ошибка БД при создании схемы", e);
        }
    }

    /** Сохранить текстовое сообщение (или общий raw JSON для неизвестного типа). */
    public void saveTextMessage(long chatId, long messageId, long date, String sender, String text, String rawJson) {
        String p = tablePrefix(chatId);
        String sql = "INSERT OR REPLACE INTO " + p + "_messages(id, date, sender, text, raw_json) VALUES(?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setLong(2, date);
            ps.setString(3, sender);
            ps.setString(4, text);
            ps.setString(5, rawJson);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("Ошибка БД при сохранении сообщения", e); }
    }

    /** Сохранить фото. */
    public void savePhoto(long chatId, long messageId, long date, String sender,
                          String fileId, String uniqueId, String remoteId,
                          int width, int height, String caption, String rawJson) {
        String p = tablePrefix(chatId);
        String sql = "INSERT OR REPLACE INTO " + p + "_photos(id, message_id, date, sender, file_id, unique_id, remote_id, width, height, caption, raw_json) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setLong(2, messageId);
            ps.setLong(3, date);
            ps.setString(4, sender);
            ps.setString(5, fileId);
            ps.setString(6, uniqueId);
            ps.setString(7, remoteId);
            ps.setInt(8, width);
            ps.setInt(9, height);
            ps.setString(10, caption);
            ps.setString(11, rawJson);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("Ошибка БД при сохранении фото", e); }
    }

    /** Сохранить видео. */
    public void saveVideo(long chatId, long messageId, long date, String sender,
                          String fileId, String uniqueId, String remoteId,
                          int duration, String fileName, String mimeType, int width, int height,
                          String caption, String rawJson) {
        String p = tablePrefix(chatId);
        String sql = "INSERT OR REPLACE INTO " + p + "_videos(id, message_id, date, sender, file_id, unique_id, remote_id, duration, file_name, mime_type, width, height, caption, raw_json) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setLong(2, messageId);
            ps.setLong(3, date);
            ps.setString(4, sender);
            ps.setString(5, fileId);
            ps.setString(6, uniqueId);
            ps.setString(7, remoteId);
            ps.setInt(8, duration);
            ps.setString(9, fileName);
            ps.setString(10, mimeType);
            ps.setInt(11, width);
            ps.setInt(12, height);
            ps.setString(13, caption);
            ps.setString(14, rawJson);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("Ошибка БД при сохранении видео", e); }
    }

    /** Сохранить аудио/голосовое. */
    public void saveAudio(long chatId, long messageId, long date, String sender, String subtype,
                          String fileId, String uniqueId, String remoteId, int duration,
                          String fileName, String mimeType, String caption, String rawJson) {
        String p = tablePrefix(chatId);
        String sql = "INSERT OR REPLACE INTO " + p + "_audio(id, message_id, date, sender, subtype, file_id, unique_id, remote_id, duration, file_name, mime_type, caption, raw_json) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setLong(2, messageId);
            ps.setLong(3, date);
            ps.setString(4, sender);
            ps.setString(5, subtype);
            ps.setString(6, fileId);
            ps.setString(7, uniqueId);
            ps.setString(8, remoteId);
            ps.setInt(9, duration);
            ps.setString(10, fileName);
            ps.setString(11, mimeType);
            ps.setString(12, caption);
            ps.setString(13, rawJson);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("Ошибка БД при сохранении аудио", e); }
    }

    /** Сохранить ссылку, извлечённую из текста/подписи. */
    public void saveLink(long chatId, long messageId, String urlValue, String snippet, String rawJson) {
        String p = tablePrefix(chatId);
        String sql = "INSERT OR IGNORE INTO " + p + "_links(message_id, url, text_snippet, raw_json) VALUES(?,?,?,?)";
        try (Connection c = DriverManager.getConnection(this.url); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setString(2, urlValue);
            ps.setString(3, snippet);
            ps.setString(4, rawJson);
            ps.executeUpdate();
        } catch (SQLException e) { log.error("Ошибка БД при сохранении ссылки", e); }
    }

    /** Префикс имени таблиц для конкретного чата (SQLite не поддерживает схемы). */
    private String tablePrefix(long chatId) { return "chat_" + Math.abs(chatId); }
}
