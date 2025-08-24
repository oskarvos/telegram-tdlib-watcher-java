package com.oleg.td;

import java.io.Closeable;
import java.sql.*;
import java.util.Objects;

public class Database implements Closeable {
    private final String url;
    private Connection conn;

    public Database(String path) {
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
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS messages(
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id          INTEGER NOT NULL,
                chat_title       TEXT,
                message_id       INTEGER NOT NULL,
                sent_at_unix     INTEGER NOT NULL,
                sender_user_id   INTEGER,
                sender_username  TEXT,
                sender_phone     TEXT,
                sender_name      TEXT,
                text             TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(chat_id, sent_at_unix);

            CREATE TABLE IF NOT EXISTS media(
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id          INTEGER NOT NULL,
                message_id       INTEGER NOT NULL,
                kind             TEXT NOT NULL,                 -- photo|video|document|other
                remote_file_id   INTEGER,                       -- TDLib file.id
                local_path       TEXT,                          -- путь на диске (TDLib)
                width            INTEGER,
                height           INTEGER,
                duration         INTEGER                         -- сек (для видео)
            );
            CREATE INDEX IF NOT EXISTS idx_media_chat_msg ON media(chat_id, message_id);

            CREATE TABLE IF NOT EXISTS links(
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id          INTEGER NOT NULL,
                message_id       INTEGER NOT NULL,
                url              TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_links_chat_msg ON links(chat_id, message_id);
            """;
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            throw new RuntimeException("SQLite init schema failed", e);
        }
    }

    public void insertMessage(long chatId, String chatTitle, long messageId, long sentAtUnix,
                              Long senderUserId, String senderUsername, String senderPhone,
                              String senderName, String text) {
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
            if (senderUserId == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, senderUserId);
            ps.setString(6, senderUsername);
            ps.setString(7, senderPhone);
            ps.setString(8, senderName);
            ps.setString(9, text);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert message failed", e);
        }
    }

    public void insertMedia(long chatId, long messageId, String kind, Long remoteFileId,
                            String localPath, Integer width, Integer height, Integer durationSec) {
        String sql = """
            INSERT INTO media(chat_id, message_id, kind, remote_file_id, local_path, width, height, duration)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setString(3, kind);
            if (remoteFileId == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, remoteFileId);
            ps.setString(5, localPath);
            if (width == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, width);
            if (height == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, height);
            if (durationSec == null) ps.setNull(8, Types.INTEGER); else ps.setInt(8, durationSec);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert media failed", e);
        }
    }

    public void insertLink(long chatId, long messageId, String url) {
        String sql = "INSERT INTO links(chat_id, message_id, url) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setString(3, url);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert link failed", e);
        }
    }

    @Override public void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }
}
