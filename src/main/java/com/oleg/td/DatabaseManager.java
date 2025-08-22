package com.oleg.td;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    private static final String CREATE_CHATS_TABLE = """
        CREATE TABLE IF NOT EXISTS chats (
            id INTEGER PRIMARY KEY,
            title TEXT,
            type TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """;

    private static final String CREATE_USERS_TABLE = """
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY,
            first_name TEXT,
            last_name TEXT,
            username TEXT,
            phone_number TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
    """;

    private static final String CREATE_MESSAGES_TABLE = """
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY,
            chat_id INTEGER,
            user_id INTEGER,
            message_text TEXT,
            media_type TEXT,
            media_path TEXT,
            links TEXT,
            timestamp TIMESTAMP,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            FOREIGN KEY (chat_id) REFERENCES chats(id),
            FOREIGN KEY (user_id) REFERENCES users(id)
        )
    """;

    private static final String CREATE_CHAT_USERS_TABLE = """
        CREATE TABLE IF NOT EXISTS chat_users (
            chat_id INTEGER,
            user_id INTEGER,
            joined_at TIMESTAMP,
            PRIMARY KEY (chat_id, user_id),
            FOREIGN KEY (chat_id) REFERENCES chats(id),
            FOREIGN KEY (user_id) REFERENCES users(id)
        )
    """;

    private Connection connection;

    public DatabaseManager(String dbPath) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initializeDatabase();
    }

    private void initializeDatabase() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(CREATE_CHATS_TABLE);
            stmt.execute(CREATE_USERS_TABLE);
            stmt.execute(CREATE_MESSAGES_TABLE);
            stmt.execute(CREATE_CHAT_USERS_TABLE);
        }
    }

    public void saveChat(long chatId, String title, String type) throws SQLException {
        String sql = "INSERT OR REPLACE INTO chats (id, title, type) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            pstmt.setString(2, title);
            pstmt.setString(3, type);
            pstmt.executeUpdate();
        }
    }

    public void saveUser(long userId, String firstName, String lastName, String username, String phoneNumber) throws SQLException {
        String sql = "INSERT OR REPLACE INTO users (id, first_name, last_name, username, phone_number) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, userId);
            pstmt.setString(2, firstName);
            pstmt.setString(3, lastName);
            pstmt.setString(4, username);
            pstmt.setString(5, phoneNumber);
            pstmt.executeUpdate();
        }
    }

    public void saveMessage(long messageId, long chatId, long userId, String text,
                            String mediaType, String mediaPath, String links, Instant timestamp) throws SQLException {
        String sql = """
            INSERT OR REPLACE INTO messages (id, chat_id, user_id, message_text, media_type, media_path, links, timestamp)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, messageId);
            pstmt.setLong(2, chatId);
            pstmt.setLong(3, userId);
            pstmt.setString(4, text);
            pstmt.setString(5, mediaType);
            pstmt.setString(6, mediaPath);
            pstmt.setString(7, links);
            pstmt.setTimestamp(8, Timestamp.from(timestamp));
            pstmt.executeUpdate();
        }
    }

    public void addChatUser(long chatId, long userId, Instant joinedAt) throws SQLException {
        String sql = "INSERT OR REPLACE INTO chat_users (chat_id, user_id, joined_at) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            pstmt.setLong(2, userId);
            pstmt.setTimestamp(3, Timestamp.from(joinedAt));
            pstmt.executeUpdate();
        }
    }

    public boolean isChatExported(long chatId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM messages WHERE chat_id = ? LIMIT 1";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            return rs.next() && rs.getInt(1) > 0;
        }
    }

    public List<Long> getMessageIds(long chatId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        String sql = "SELECT id FROM messages WHERE chat_id = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setLong(1, chatId);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            log.warn("Failed to close database connection", e);
        }
    }
}