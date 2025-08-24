package com.oleg.td;

import java.io.Closeable;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class Database implements Closeable {
    private final String url;
    private Connection conn;

    // Форматы даты/времени (UTC) для новых колонок
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

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

    @Override public void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    // -------------------- СХЕМА / МИГРАЦИЯ --------------------

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
        } catch (SQLException e) {
            throw new RuntimeException("SQLite pragma failed", e);
        }

        migrateMessagesIfNeeded();
        migrateMediaIfNeeded();
        createLinksIfNeeded();
    }

    private void migrateMessagesIfNeeded() {
        // Новая схема: БЕЗ message_id, БЕЗ sent_at_unix; ДОБАВЛЯЕМ msg_date, msg_time
        boolean tableExists = tableExists("messages");
        if (!tableExists) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS messages(
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id          INTEGER NOT NULL,
                        chat_title       TEXT,
                        msg_date         TEXT NOT NULL,          -- YYYY-MM-DD (UTC)
                        msg_time         TEXT NOT NULL,          -- HH:MM:SS (UTC)
                        sender_user_id   INTEGER,
                        sender_username  TEXT,
                        sender_phone     TEXT,
                        sender_name      TEXT,
                        text             TEXT
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(chat_id, msg_date, msg_time)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_dedup
                    ON messages(chat_id, msg_date, msg_time, COALESCE(sender_user_id, -1), COALESCE(text, ''))
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Create messages failed", e);
            }
            return;
        }

        Set<String> cols = columnsOf("messages");
        boolean needMigration =
                cols.contains("message_id") || cols.contains("sent_at_unix")
                        || !cols.contains("msg_date") || !cols.contains("msg_time");

        if (!needMigration) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(chat_id, msg_date, msg_time)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_dedup
                    ON messages(chat_id, msg_date, msg_time, COALESCE(sender_user_id, -1), COALESCE(text, ''))
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Ensure messages indexes failed", e);
            }
            return;
        }

        // Миграция в новую структуру + дедуп
        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN");
            st.execute("""
                CREATE TABLE IF NOT EXISTS messages_new(
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          INTEGER NOT NULL,
                    chat_title       TEXT,
                    msg_date         TEXT NOT NULL,
                    msg_time         TEXT NOT NULL,
                    sender_user_id   INTEGER,
                    sender_username  TEXT,
                    sender_phone     TEXT,
                    sender_name      TEXT,
                    text             TEXT
                )
            """);

            if (cols.contains("sent_at_unix")) {
                st.execute("""
                    INSERT INTO messages_new(id, chat_id, chat_title, msg_date, msg_time,
                                             sender_user_id, sender_username, sender_phone, sender_name, text)
                    SELECT id, chat_id, chat_title,
                           strftime('%Y-%m-%d', sent_at_unix, 'unixepoch'),
                           strftime('%H:%M:%S', sent_at_unix, 'unixepoch'),
                           sender_user_id, sender_username, sender_phone, sender_name, text
                    FROM messages
                """);
            } else {
                st.execute("""
                    INSERT INTO messages_new(id, chat_id, chat_title, msg_date, msg_time,
                                             sender_user_id, sender_username, sender_phone, sender_name, text)
                    SELECT id, chat_id, chat_title,
                           date('now'), time('now'),
                           sender_user_id, sender_username, sender_phone, sender_name, text
                    FROM messages
                """);
            }

            // Удаляем дубли перед созданием UNIQUE индекса
            st.execute("""
                DELETE FROM messages_new
                WHERE rowid NOT IN (
                    SELECT MIN(rowid) FROM messages_new
                    GROUP BY chat_id, msg_date, msg_time,
                             COALESCE(sender_user_id, -1),
                             COALESCE(text, '')
                )
            """);

            st.execute("DROP TABLE messages");
            st.execute("ALTER TABLE messages_new RENAME TO messages");
            st.execute("CREATE INDEX IF NOT EXISTS idx_messages_chat_time ON messages(chat_id, msg_date, msg_time)");
            st.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_dedup
                ON messages(chat_id, msg_date, msg_time, COALESCE(sender_user_id, -1), COALESCE(text, ''))
            """);
            st.execute("COMMIT");
        } catch (SQLException e) {
            try { conn.createStatement().execute("ROLLBACK"); } catch (SQLException ignored) {}
            throw new RuntimeException("Migrate messages failed", e);
        }
    }

    private void migrateMediaIfNeeded() {
        // Новая схема: БЕЗ message_id; ДОБАВЛЯЕМ media_date, media_time
        boolean tableExists = tableExists("media");
        if (!tableExists) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS media(
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id          INTEGER NOT NULL,
                        kind             TEXT NOT NULL,          -- photo|video|document|animation|audio|voice_note|sticker|video_note
                        remote_file_id   INTEGER,
                        local_path       TEXT,
                        width            INTEGER,
                        height           INTEGER,
                        duration         INTEGER,
                        media_date       TEXT NOT NULL DEFAULT (date('now')),
                        media_time       TEXT NOT NULL DEFAULT (time('now'))
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_chat_kind ON media(chat_id, kind)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_media_dedup
                    ON media(chat_id, kind, COALESCE(remote_file_id, -1), COALESCE(local_path, ''))
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Create media failed", e);
            }
            return;
        }

        Set<String> cols = columnsOf("media");
        boolean needMigration =
                cols.contains("message_id") || !cols.contains("media_date") || !cols.contains("media_time");

        if (!needMigration) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_chat_kind ON media(chat_id, kind)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_media_dedup
                    ON media(chat_id, kind, COALESCE(remote_file_id, -1), COALESCE(local_path, ''))
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Ensure media indexes failed", e);
            }
            return;
        }

        // Миграция + дедуп
        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN");
            st.execute("""
                CREATE TABLE IF NOT EXISTS media_new(
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          INTEGER NOT NULL,
                    kind             TEXT NOT NULL,
                    remote_file_id   INTEGER,
                    local_path       TEXT,
                    width            INTEGER,
                    height           INTEGER,
                    duration         INTEGER,
                    media_date       TEXT NOT NULL DEFAULT (date('now')),
                    media_time       TEXT NOT NULL DEFAULT (time('now'))
                )
            """);

            st.execute("""
                INSERT INTO media_new(id, chat_id, kind, remote_file_id, local_path, width, height, duration)
                SELECT id, chat_id, kind, remote_file_id, local_path, width, height, duration
                FROM media
            """);

            // Удаляем дубли перед созданием UNIQUE индекса
            st.execute("""
                DELETE FROM media_new
                WHERE rowid NOT IN (
                    SELECT MIN(rowid) FROM media_new
                    GROUP BY chat_id, kind,
                             COALESCE(remote_file_id, -1),
                             COALESCE(local_path, '')
                )
            """);

            st.execute("DROP TABLE media");
            st.execute("ALTER TABLE media_new RENAME TO media");
            st.execute("CREATE INDEX IF NOT EXISTS idx_media_chat_kind ON media(chat_id, kind)");
            st.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_media_dedup
                ON media(chat_id, kind, COALESCE(remote_file_id, -1), COALESCE(local_path, ''))
            """);
            st.execute("COMMIT");
        } catch (SQLException e) {
            try { conn.createStatement().execute("ROLLBACK"); } catch (SQLException ignored) {}
            throw new RuntimeException("Migrate media failed", e);
        }
    }

    private void createLinksIfNeeded() {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS links(
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          INTEGER NOT NULL,
                    message_id       INTEGER NOT NULL,
                    url              TEXT NOT NULL
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_links_chat_msg ON links(chat_id, message_id)");
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_links_chat_msg_url ON links(chat_id, message_id, url)");
        } catch (SQLException e) {
            throw new RuntimeException("Create/ensure links failed", e);
        }
    }

    private boolean tableExists(String name) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"
        )) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("tableExists failed", e);
        }
    }

    private Set<String> columnsOf(String table) {
        Set<String> cols = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("PRAGMA table_info(" + table + ")")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) cols.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("columnsOf failed for " + table, e);
        }
        return cols;
    }

    // -------------------- ВСТАВКИ --------------------

    public void insertMessage(long chatId, String chatTitle, long messageId /*не используется*/, long sentAtUnix,
                              Long senderUserId, String senderUsername, String senderPhone,
                              String senderName, String text) {
        // Конвертируем sentAtUnix -> msg_date/msg_time (UTC)
        String msgDate = (sentAtUnix > 0) ? DATE_FMT.format(Instant.ofEpochSecond(sentAtUnix)) : DATE_FMT.format(Instant.now());
        String msgTime = (sentAtUnix > 0) ? TIME_FMT.format(Instant.ofEpochSecond(sentAtUnix)) : TIME_FMT.format(Instant.now());

        String sql = """
            INSERT OR IGNORE INTO messages(chat_id, chat_title, msg_date, msg_time,
                                           sender_user_id, sender_username, sender_phone, sender_name, text)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, chatTitle);
            ps.setString(3, msgDate);
            ps.setString(4, msgTime);
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

    public void insertMedia(long chatId, long messageId /*не используется*/, String kind, Long remoteFileId,
                            String localPath, Integer width, Integer height, Integer durationSec) {
        // Дату/время для media берём "сейчас" (у вызова нет unix-времени сообщения)
        String mediaDate = DATE_FMT.format(Instant.now());
        String mediaTime = TIME_FMT.format(Instant.now());

        String sql = """
            INSERT OR IGNORE INTO media(chat_id, kind, remote_file_id, local_path, width, height, duration, media_date, media_time)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, kind);
            if (remoteFileId == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, remoteFileId);
            ps.setString(4, localPath);
            if (width == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, width);
            if (height == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, height);
            if (durationSec == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, durationSec);
            ps.setString(8, mediaDate);
            ps.setString(9, mediaTime);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert media failed", e);
        }
    }

    public void insertLink(long chatId, long messageId, String url) {
        String sql = "INSERT OR IGNORE INTO links(chat_id, message_id, url) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setString(3, url);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert link failed", e);
        }
    }
}
