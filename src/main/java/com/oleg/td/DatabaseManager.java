package com.oleg.td;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

@Component
public class DatabaseManager {
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    private final String url = "jdbc:sqlite:tdlib.db";

    public void prepareSchema(long chatId) {
        String schema = "chat_" + chatId;
        try (Connection connection = DriverManager.getConnection(url);
             Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + schema + "_messages(id INTEGER PRIMARY KEY, content TEXT)");
        } catch (SQLException e) {
            log.error("DB error", e);
        }
    }
}