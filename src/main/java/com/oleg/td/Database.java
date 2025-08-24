package com.oleg.td;

import java.io.Closeable;
import java.sql.*;
import java.util.Objects;

/**
 * Простая обёртка над SQLite. Создаёт таблицу при первом запуске.
 */
public class Database implements Closeable {
    private final String url;
    private Connection conn;

    public Database(String path) {
        // JDBC URL, файл создастся автоматически при первом подключении
        this.url = "jdbc:sqlite:" + Objects.requireNonNull(path);
        connect();
        initSchema();
    }

    private void connect() {
        try {
            this.conn = DriverManager.getConnection(url);
            this.conn.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException("SQLite connect failed: " + url, e);
        }
    }

    private void initSchema() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS messages(
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          INTEGER NOT NULL,
                    chat_title       TEXT,
                    message_id       INTEGER NOT NULL,
                    sent_at_unix     INTEGER NOT NULL,   -- секунды UNIX (из TDLib)
                    sender_user_id   INTEGER,
                    sender_username  TEXT,
                    sender_phone     TEXT,
                    sender_name      TEXT,
                    text             TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(chat_id, sent_at_unix);
                """;
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new RuntimeException("SQLite init schema failed", e);
        }
    }

    public void insertMessage(long chatId,
                              String chatTitle,
                              long messageId,
                              long sentAtUnix,
                              Long senderUserId,
                              String senderUsername,
                              String senderPhone,
                              String senderName,
                              String text) {
        String sql = """
                INSERT INTO messages(chat_id, chat_title, message_id, sent_at_unix,
                                     sender_user_id, sender_username, sender_phone, sender_name, text)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, chatTitle);
            ps.setLong(3, messageId);
            ps.setLong(4, sentAtUnix);
            if (senderUserId == null) ps.setNull(5, Types.BIGINT);
            else ps.setLong(5, senderUserId);
            ps.setString(6, senderUsername);
            ps.setString(7, senderPhone);
            ps.setString(8, senderName);
            ps.setString(9, text);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert failed", e);
        }
    }

    @Override
    public void close() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException ignored) {
        }
    }
}
