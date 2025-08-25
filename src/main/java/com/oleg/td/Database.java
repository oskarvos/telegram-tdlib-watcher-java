package com.oleg.td;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // Форматы даты/времени (UTC): dd-MM-yyyy / HH:mm:ss
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

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

    @Override
    public void close() {
        try { if (conn != null) conn.close(); } catch (SQLException ignored) {}
    }

    // =========================== СХЕМА / МИГРАЦИИ ===========================

    private void initSchema() {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
        } catch (SQLException e) {
            throw new RuntimeException("SQLite pragma failed", e);
        }

        migrateMessagesIfNeeded();
        migrateMediaIfNeeded();
        migrateLinksIfNeeded();
        createMessageMetaIfNeeded();
        createBootFlagsIfNeeded();
    }

    // ---------- messages ----------
    private void migrateMessagesIfNeeded() {
        boolean exists = tableExists("messages");
        if (!exists) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS messages(
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id          INTEGER NOT NULL,
                        chat_title       TEXT,
                        msg_date         TEXT NOT NULL,      -- dd-MM-yyyy
                        msg_time         TEXT NOT NULL,      -- HH:mm:ss
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
        boolean need =
                cols.contains("message_id") || cols.contains("sent_at_unix")
                        || !cols.contains("msg_date") || !cols.contains("msg_time");

        if (!need) {
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
                           strftime('%d-%m-%Y', sent_at_unix, 'unixepoch'),
                           strftime('%H:%M:%S', sent_at_unix, 'unixepoch'),
                           sender_user_id, sender_username, sender_phone, sender_name, text
                    FROM messages
                """);
            } else {
                st.execute("""
                    INSERT INTO messages_new(id, chat_id, chat_title, msg_date, msg_time,
                                             sender_user_id, sender_username, sender_phone, sender_name, text)
                    SELECT id, chat_id, chat_title,
                           strftime('%d-%m-%Y','now'),
                           strftime('%H:%M:%S','now'),
                           sender_user_id, sender_username, sender_phone, sender_name, text
                    FROM messages
                """);
            }

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

    // ---------- media: chat_id, media_date, media_time, sender_name, kind, local_path, file_size ----------
    private void migrateMediaIfNeeded() {
        boolean exists = tableExists("media");
        if (!exists) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS media(
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id          INTEGER NOT NULL,
                        media_date       TEXT NOT NULL DEFAULT (strftime('%d-%m-%Y','now')),
                        media_time       TEXT NOT NULL DEFAULT (strftime('%H:%M:%S','now')),
                        sender_name      TEXT,
                        kind             TEXT NOT NULL,
                        local_path       TEXT,
                        file_size        INTEGER
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_dt ON media(media_date, media_time)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_chat_kind ON media(chat_id, kind)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_media_dedup
                    ON media(chat_id, kind, COALESCE(local_path,''), COALESCE(file_size,-1))
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Create media failed", e);
            }
            return;
        }

        Set<String> cols = columnsOf("media");
        boolean need =
                cols.contains("message_id") || cols.contains("remote_file_id")
                        || cols.contains("width") || cols.contains("height") || cols.contains("duration")
                        || !cols.contains("media_date") || !cols.contains("media_time")
                        || !cols.contains("sender_name") || !cols.contains("file_size")
                        || !cols.contains("kind") || !cols.contains("local_path");

        if (!need) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_dt ON media(media_date, media_time)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_chat_kind ON media(chat_id, kind)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_media_dedup
                    ON media(chat_id, kind, COALESCE(local_path,''), COALESCE(file_size,-1))
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Ensure media indexes failed", e);
            }
            return;
        }

        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN");
            st.execute("""
                CREATE TABLE IF NOT EXISTS media_new(
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          INTEGER NOT NULL,
                    media_date       TEXT NOT NULL DEFAULT (strftime('%d-%m-%Y','now')),
                    media_time       TEXT NOT NULL DEFAULT (strftime('%H:%M:%S','now')),
                    sender_name      TEXT,
                    kind             TEXT NOT NULL,
                    local_path       TEXT,
                    file_size        INTEGER
                )
            """);

            if (cols.contains("media_date") && cols.contains("media_time") && cols.contains("kind") && cols.contains("local_path")) {
                st.execute("""
                    INSERT INTO media_new(id, chat_id, media_date, media_time, sender_name, kind, local_path, file_size)
                    SELECT id, chat_id, media_date, media_time, NULL, kind, local_path, NULL
                    FROM media
                """);
            } else if (cols.contains("kind") && cols.contains("local_path")) {
                st.execute("""
                    INSERT INTO media_new(id, chat_id, media_date, media_time, sender_name, kind, local_path, file_size)
                    SELECT id, chat_id, strftime('%d-%m-%Y','now'), strftime('%H:%M:%S','now'), NULL, kind, local_path, NULL
                    FROM media
                """);
            } else {
                st.execute("""
                    INSERT INTO media_new(id, chat_id, media_date, media_time, sender_name, kind, local_path, file_size)
                    SELECT id, chat_id, strftime('%d-%m-%Y','now'), strftime('%H:%M:%S','now'), NULL, 'document', NULL, NULL
                    FROM media
                """);
            }

            st.execute("""
                DELETE FROM media_new
                WHERE rowid NOT IN (
                    SELECT MIN(rowid) FROM media_new
                    GROUP BY chat_id, kind, COALESCE(local_path,''), COALESCE(file_size,-1)
                )
            """);

            st.execute("DROP TABLE media");
            st.execute("ALTER TABLE media_new RENAME TO media");
            st.execute("CREATE INDEX IF NOT EXISTS idx_media_dt ON media(media_date, media_time)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_media_chat_kind ON media(chat_id, kind)");
            st.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_media_dedup
                ON media(chat_id, kind, COALESCE(local_path,''), COALESCE(file_size,-1))
            """);
            st.execute("COMMIT");
        } catch (SQLException e) {
            try { conn.createStatement().execute("ROLLBACK"); } catch (SQLException ignored) {}
            throw new RuntimeException("Migrate media failed", e);
        }
    }

    // ---------- links: chat_id, link_date, link_time, sender_name, url ----------
    private void migrateLinksIfNeeded() {
        boolean exists = tableExists("links");
        if (!exists) {
            try (Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS links(
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        chat_id          INTEGER NOT NULL,
                        link_date        TEXT NOT NULL DEFAULT (strftime('%d-%m-%Y','now')),
                        link_time        TEXT NOT NULL DEFAULT (strftime('%H:%M:%S','now')),
                        sender_name      TEXT,
                        url              TEXT NOT NULL
                    )
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_links_dt ON links(link_date, link_time)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_links_dedup
                    ON links(chat_id, url, link_date, link_time)
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Create links failed", e);
            }
            return;
        }

        Set<String> cols = columnsOf("links");
        boolean need =
                cols.contains("message_id") || !cols.contains("link_date") || !cols.contains("link_time")
                        || !cols.contains("sender_name") || !cols.contains("url");

        if (!need) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_links_dt ON links(link_date, link_time)");
                st.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS uq_links_dedup
                    ON links(chat_id, url, link_date, link_time)
                """);
            } catch (SQLException e) {
                throw new RuntimeException("Ensure links indexes failed", e);
            }
            return;
        }

        try (Statement st = conn.createStatement()) {
            st.execute("BEGIN");
            st.execute("""
                CREATE TABLE IF NOT EXISTS links_new(
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id          INTEGER NOT NULL,
                    link_date        TEXT NOT NULL DEFAULT (strftime('%d-%m-%Y','now')),
                    link_time        TEXT NOT NULL DEFAULT (strftime('%H:%M:%S','now')),
                    sender_name      TEXT,
                    url              TEXT NOT NULL
                )
            """);

            if (cols.contains("link_date") && cols.contains("link_time")) {
                st.execute("""
                    INSERT INTO links_new(id, chat_id, link_date, link_time, sender_name, url)
                    SELECT id, chat_id, link_date, link_time, NULL, url
                    FROM links
                """);
            } else {
                st.execute("""
                    INSERT INTO links_new(id, chat_id, link_date, link_time, sender_name, url)
                    SELECT id, chat_id, strftime('%d-%m-%Y','now'), strftime('%H:%M:%S','now'), NULL, url
                    FROM links
                """);
            }

            st.execute("""
                DELETE FROM links_new
                WHERE rowid NOT IN (
                    SELECT MIN(rowid) FROM links_new
                    GROUP BY chat_id, url, link_date, link_time
                )
            """);

            st.execute("DROP TABLE links");
            st.execute("ALTER TABLE links_new RENAME TO links");
            st.execute("CREATE INDEX IF NOT EXISTS idx_links_dt ON links(link_date, link_time)");
            st.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_links_dedup
                ON links(chat_id, url, link_date, link_time)
            """);
            st.execute("COMMIT");
        } catch (SQLException e) {
            try { conn.createStatement().execute("ROLLBACK"); } catch (SQLException ignored) {}
            throw new RuntimeException("Migrate links failed", e);
        }
    }

    // ---------- message_meta ----------
    private void createMessageMetaIfNeeded() {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS message_meta(
                    chat_id          INTEGER NOT NULL,
                    message_id       INTEGER NOT NULL,
                    msg_date         TEXT NOT NULL,
                    msg_time         TEXT NOT NULL,
                    sender_user_id   INTEGER,
                    sender_username  TEXT,
                    sender_phone     TEXT,
                    sender_name      TEXT,
                    PRIMARY KEY(chat_id, message_id)
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_meta_chat_msg ON message_meta(chat_id, message_id)");
        } catch (SQLException e) {
            throw new RuntimeException("Create message_meta failed", e);
        }
    }

    // ---------- boot_flags ----------
    private void createBootFlagsIfNeeded() {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS boot_flags(
                    chat_id INTEGER PRIMARY KEY,
                    dumped  INTEGER NOT NULL DEFAULT 0
                )
            """);
        } catch (SQLException e) {
            throw new RuntimeException("Create boot_flags failed", e);
        }
    }

    public boolean isChatBootstrapped(long chatId) {
        String sql = "SELECT dumped FROM boot_flags WHERE chat_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            throw new RuntimeException("boot_flags check failed", e);
        }
    }

    public void markChatBootstrapped(long chatId) {
        String sql = """
            INSERT INTO boot_flags(chat_id, dumped) VALUES(?,1)
            ON CONFLICT(chat_id) DO UPDATE SET dumped=1
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("boot_flags upsert failed", e);
        }
    }

    // =========================== ВСТАВКИ ===========================

    public void insertMessage(long chatId, String chatTitle, long messageId, long sentAtUnix,
                              Long senderUserId, String senderUsername, String senderPhone,
                              String senderName, String text) {
        String msgDate = (sentAtUnix > 0)
                ? DATE_FMT.format(Instant.ofEpochSecond(sentAtUnix))
                : DATE_FMT.format(Instant.now());
        String msgTime = (sentAtUnix > 0)
                ? TIME_FMT.format(Instant.ofEpochSecond(sentAtUnix))
                : TIME_FMT.format(Instant.now());

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

        upsertMessageMeta(chatId, messageId, msgDate, msgTime, senderUserId, senderUsername, senderPhone, senderName);
    }

    public void insertMedia(long chatId, long messageId, String kind, Long remoteFileId,
                            String localPath, Integer width, Integer height, Integer durationSec) {
        Meta meta = fetchMeta(chatId, messageId);
        String mediaDate = (meta != null) ? meta.msgDate : DATE_FMT.format(Instant.now());
        String mediaTime = (meta != null) ? meta.msgTime : TIME_FMT.format(Instant.now());
        String senderName = (meta != null) ? meta.senderName : null;

        Long fileSize = null;
        try {
            if (localPath != null && !localPath.isBlank()) {
                Path p = Path.of(localPath);
                if (Files.exists(p)) fileSize = Files.size(p);
            }
        } catch (Exception ignored) {}

        String sql = """
            INSERT OR IGNORE INTO media(chat_id, media_date, media_time, sender_name, kind, local_path, file_size)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, mediaDate);
            ps.setString(3, mediaTime);
            ps.setString(4, senderName);
            ps.setString(5, kind);
            ps.setString(6, localPath);
            if (fileSize == null) ps.setNull(7, Types.BIGINT); else ps.setLong(7, fileSize);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert media failed", e);
        }
    }

    public void insertLink(long chatId, long messageId, String url) {
        Meta meta = fetchMeta(chatId, messageId);
        String linkDate = (meta != null) ? meta.msgDate : DATE_FMT.format(Instant.now());
        String linkTime = (meta != null) ? meta.msgTime : TIME_FMT.format(Instant.now());
        String senderName = (meta != null) ? meta.senderName : null;

        String sql = """
            INSERT OR IGNORE INTO links(chat_id, link_date, link_time, sender_name, url)
            VALUES (?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setString(2, linkDate);
            ps.setString(3, linkTime);
            ps.setString(4, senderName);
            ps.setString(5, url);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite insert link failed", e);
        }
    }

    // =========================== message_meta helpers ===========================

    private void upsertMessageMeta(long chatId, long messageId, String msgDate, String msgTime,
                                   Long senderUserId, String senderUsername, String senderPhone, String senderName) {
        String sql = """
            INSERT INTO message_meta(chat_id, message_id, msg_date, msg_time, sender_user_id, sender_username, sender_phone, sender_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(chat_id, message_id) DO UPDATE SET
                msg_date=excluded.msg_date,
                msg_time=excluded.msg_time,
                sender_user_id=excluded.sender_user_id,
                sender_username=excluded.sender_username,
                sender_phone=excluded.sender_phone,
                sender_name=excluded.sender_name
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setString(3, msgDate);
            ps.setString(4, msgTime);
            if (senderUserId == null) ps.setNull(5, Types.BIGINT); else ps.setLong(5, senderUserId);
            ps.setString(6, senderUsername);
            ps.setString(7, senderPhone);
            ps.setString(8, senderName);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SQLite upsert message_meta failed", e);
        }
    }

    private static final class Meta {
        final String msgDate, msgTime, senderName;
        Meta(String d, String t, String n) { msgDate = d; msgTime = t; senderName = n; }
    }

    private Meta fetchMeta(long chatId, long messageId) {
        String sql = "SELECT msg_date, msg_time, sender_name FROM message_meta WHERE chat_id=? AND message_id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Meta(rs.getString(1), rs.getString(2), rs.getString(3));
            }
        } catch (SQLException ignored) {}
        return null;
    }

    // =========================== Утилиты ===========================

    private boolean tableExists(String name) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
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
}
