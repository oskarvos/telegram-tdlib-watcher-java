package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.*;

/**
 * Менеджер работы с SQLite.
 * На каждый чат создаются отдельные таблицы с префиксом chat_{chatId}_...
 * Таблицы:
 *  - ..._messages (все текстовые сообщения и метаданные)
 *  - ..._photos   (метаданные фото: file_id, подпись)
 *  - ..._videos   (метаданные видео: file_id, подпись)
 *  - ..._audio    (голосовые/аудиосообщения)
 *  - ..._links    (все ссылки, извлечённые из текста/подписей)
 *
 * Примечание: загрузка самих файлов TDLib (скачивание) здесь не реализована — мы сохраняем ID файла,
 * чтобы можно было докачать отдельно (через downloadFile).
 */
@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    // Файл БД. Можно вынести в конфиг.
    private final String url = "jdbc:sqlite:tdlib.db";

    /**
     * Создать (если нет) все таблицы под конкретный чат.
     * @param chatId ID чата
     */
    public void prepareSchema(long chatId) {
        final String p = "chat_" + chatId; // префикс

        final String createMessages = "CREATE TABLE IF NOT EXISTS " + p + "_messages (" +
                "id INTEGER PRIMARY KEY," +
                "date INTEGER," +
                "sender_id TEXT," +
                "reply_to INTEGER," +
                "text TEXT" +
                ")";
        final String createPhotos = "CREATE TABLE IF NOT EXISTS " + p + "_photos (" +
                "message_id INTEGER," +
                "file_id INTEGER," +      // внутренний TDLib file.id (int)
                "remote_id TEXT," +       // удалённый id, если TDLib его вернул
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
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
                "PRIMARY KEY (message_id)" +
                ")";
        final String createAudio = "CREATE TABLE IF NOT EXISTS " + p + "_audio (" +
                "message_id INTEGER," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "mime_type TEXT," +
                "PRIMARY KEY (message_id)" +
                ")";
        final String createLinks = "CREATE TABLE IF NOT EXISTS " + p + "_links (" +
                "message_id INTEGER," +
                "url TEXT," +
                "context TEXT" +          // фрагмент текста/подписи, в котором обнаружена ссылка
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
            log.info("БД: схема для чата {} успешно подготовлена", chatId);
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    /** Сохранить текстовое сообщение и базовые поля. */
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

    /** Сохранить фото (метаданные). */
    public void savePhoto(long chatId, long messageId, Integer fileId, String remoteId, Integer w, Integer h, String caption) {
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR REPLACE INTO " + p + "_photos(message_id, file_id, remote_id, width, height, caption) VALUES(?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER); else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (w == null) st.setNull(4, Types.INTEGER); else st.setInt(4, w);
            if (h == null) st.setNull(5, Types.INTEGER); else st.setInt(5, h);
            st.setString(6, caption);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения фото (message_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /** Сохранить видео (метаданные). */
    public void saveVideo(long chatId, long messageId, Integer fileId, String remoteId, Integer duration, Integer w, Integer h, String caption) {
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR REPLACE INTO " + p + "_videos(message_id, file_id, remote_id, duration, width, height, caption) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER); else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER); else st.setInt(4, duration);
            if (w == null) st.setNull(5, Types.INTEGER); else st.setInt(5, w);
            if (h == null) st.setNull(6, Types.INTEGER); else st.setInt(6, h);
            st.setString(7, caption);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения видео (message_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /** Сохранить аудиосообщение (voice note / audio). */
    public void saveAudio(long chatId, long messageId, Integer fileId, String remoteId, Integer duration, String mime) {
        final String p = "chat_" + chatId;
        final String sql = "INSERT OR REPLACE INTO " + p + "_audio(message_id, file_id, remote_id, duration, mime_type) VALUES(?,?,?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER); else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER); else st.setInt(4, duration);
            st.setString(5, mime);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения аудио (message_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /** Сохранить найденную ссылку. */
    public void saveLink(long chatId, long messageId, String url, String context) {
        final String p = "chat_" + chatId;
        final String sql = "INSERT INTO " + p + "_links(message_id, url, context) VALUES(?,?,?)";
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setString(2, url);
            st.setString(3, context);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения ссылки (message_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }
}
