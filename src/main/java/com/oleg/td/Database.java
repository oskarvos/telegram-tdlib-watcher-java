package com.oleg.td;

import java.io.Closeable;
import java.nio.file.Path;
import java.sql.*;

public class Database implements Closeable {
    private final Connection cx;

    public Database(Path path) throws Exception {
        Class.forName("org.sqlite.JDBC");
        String url = "jdbc:sqlite:" + path.toAbsolutePath();
        this.cx = DriverManager.getConnection(url);
        try (Statement st = cx.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
        }
        ensureSchema();
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = cx.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS chats(
                    chat_id INTEGER PRIMARY KEY,
                    title TEXT,
                    username TEXT,
                    type TEXT,
                    last_sync_message_id INTEGER DEFAULT 0,
                    last_sync_ts INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS users(
                    user_id INTEGER PRIMARY KEY,
                    first_name TEXT,
                    last_name TEXT,
                    username TEXT,
                    phone TEXT,
                    is_bot INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS chat_members(
                    chat_id INTEGER,
                    user_id INTEGER,
                    status TEXT,
                    joined_date INTEGER,
                    PRIMARY KEY(chat_id,user_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS messages(
                    chat_id INTEGER,
                    message_id INTEGER,
                    date INTEGER,
                    author_id INTEGER,
                    content_type TEXT,
                    text TEXT,
                    raw_json TEXT,
                    PRIMARY KEY(chat_id, message_id)
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS media(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id INTEGER,
                    message_id INTEGER,
                    type TEXT,
                    file_id INTEGER,
                    remote_id TEXT,
                    local_path TEXT,
                    width INTEGER,
                    height INTEGER,
                    duration INTEGER,
                    mime_type TEXT,
                    size INTEGER
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS links(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id INTEGER,
                    message_id INTEGER,
                    url TEXT
                )""");
        }
    }

    public void upsertChat(long chatId, String title, String username, String type) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO chats(chat_id,title,username,type,last_sync_ts)
            VALUES(?,?,?,?,strftime('%s','now'))
            ON CONFLICT(chat_id) DO UPDATE SET
                title=excluded.title,
                username=excluded.username,
                type=excluded.type,
                last_sync_ts=strftime('%s','now')
        """)) {
            ps.setLong(1, chatId);
            ps.setString(2, title);
            ps.setString(3, username);
            ps.setString(4, type);
            ps.executeUpdate();
        }
    }

    public void upsertUser(long userId, String first, String last, String username, String phone, boolean isBot) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO users(user_id,first_name,last_name,username,phone,is_bot)
            VALUES(?,?,?,?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET
                first_name=excluded.first_name,
                last_name=excluded.last_name,
                username=excluded.username,
                phone=excluded.phone,
                is_bot=excluded.is_bot
        """)) {
            ps.setLong(1, userId);
            ps.setString(2, first);
            ps.setString(3, last);
            ps.setString(4, username);
            ps.setString(5, phone);
            ps.setInt(6, isBot ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void upsertChatMember(long chatId, long userId, String status, int joined) throws SQLException {
        if (userId == 0) return;
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO chat_members(chat_id,user_id,status,joined_date)
            VALUES(?,?,?,?)
            ON CONFLICT(chat_id,user_id) DO UPDATE SET
                status=excluded.status,
                joined_date=excluded.joined_date
        """)) {
            ps.setLong(1, chatId);
            ps.setLong(2, userId);
            ps.setString(3, status);
            ps.setInt(4, joined);
            ps.executeUpdate();
        }
    }

    public void upsertMessage(long chatId, long messageId, int date, long authorId,
                              String contentType, String text, String rawJson) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO messages(chat_id,message_id,date,author_id,content_type,text,raw_json)
            VALUES(?,?,?,?,?,?,?)
            ON CONFLICT(chat_id,message_id) DO NOTHING
        """)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setInt(3, date);
            ps.setLong(4, authorId);
            ps.setString(5, contentType);
            ps.setString(6, text);
            ps.setString(7, rawJson);
            ps.executeUpdate();
        }
    }

    public void upsertMedia(long chatId, long messageId, String type, int fileId, String remoteId,
                            String localPath, int w, int h, int dur, String mime, long size) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO media(chat_id,message_id,type,file_id,remote_id,local_path,width,height,duration,mime_type,size)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)
        """)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setString(3, type);
            ps.setInt(4, fileId);
            ps.setString(5, remoteId);
            ps.setString(6, localPath);
            ps.setInt(7, w);
            ps.setInt(8, h);
            ps.setInt(9, dur);
            ps.setString(10, mime);
            ps.setLong(11, size);
            ps.executeUpdate();
        }
    }

    public void insertLink(long chatId, long messageId, String url) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            INSERT INTO links(chat_id,message_id,url) VALUES(?,?,?)
        """)) {
            ps.setLong(1, chatId);
            ps.setLong(2, messageId);
            ps.setString(3, url);
            ps.executeUpdate();
        }
    }

    public long getMaxMessageId(long chatId) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("SELECT COALESCE(MAX(message_id),0) FROM messages WHERE chat_id=?")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : 0L; }
        }
    }

    public void setChatSyncPosition(long chatId, long lastMsgId) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement("""
            UPDATE chats SET last_sync_message_id=?, last_sync_ts=strftime('%s','now') WHERE chat_id=?
        """)) {
            ps.setLong(1, lastMsgId);
            ps.setLong(2, chatId);
            ps.executeUpdate();
        }
    }

    @Override public void close() {
        try { cx.close(); } catch (Exception ignored) {}
    }
}
