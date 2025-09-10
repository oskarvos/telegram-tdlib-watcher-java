package com.oleg.td.dump.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.sql.*;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class DumpDbManager {
    private static final Logger log = LoggerFactory.getLogger(DumpDbManager.class);

    private final Path dbDir = Paths.get("tdlib", "db");
    private final ChatResolver chatResolver;

    public DumpDbManager(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
        try { Files.createDirectories(dbDir); } catch (Exception ignored) {}
    }

    // --- helpers
    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|]");
    private static String q(String ident){ return "\"" + ident.replace("\"","\"\"") + "\""; }

    private String chatName(long chatId){
        try {
            String t = chatResolver.getChatTitle(chatId);
            if (t != null && !t.trim().isEmpty()) return t.trim();
        } catch (Exception ignored) {}
        return "chat_" + Math.abs(chatId);
    }

    private String safe(String name, long chatId){
        if (name == null || name.isBlank()) return "unknown_chat";
        String s = INVALID.matcher(name).replaceAll("_").trim();
        while (s.endsWith(".")) s = s.substring(0, s.length()-1).trim();
        if (s.isEmpty()) s = "chat_" + Math.abs(chatId);
        if (s.length() > 100) s = s.substring(0, 100);
        return s;
    }

    public Path dumpDbPath(long chatId){
        String fn = safe("DUMP " + chatName(chatId), chatId) + ".db";
        return dbDir.resolve(fn);
    }

    public Connection openDump(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dumpDbPath(chatId));
    }

    // --- metadata (общая таблица в DUMP *.db)
    public void ensureDumpMetadata(long chatId){
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e){
            log.error("DUMP {}: cannot ensure metadata: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    public String getMetadata(long chatId, String key){
        final String sql = "SELECT value FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()){
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e){ log.warn("DUMP get meta '{}' err: {}", key, e.getMessage()); }
        return null;
    }

    public void setMetadata(long chatId, String key, String value){
        final String sql = "INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("DUMP set meta '{}' err: {}", key, e.getMessage(), e); }
    }

    public void deleteMetadata(long chatId, String key){
        final String sql = "DELETE FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("DUMP del meta '{}' err: {}", key, e.getMessage(), e); }
    }

    // Удобные врапперы под чекпоинты (если где-то используются напрямую)
    public String loadSearchCheckpoint(long chatId){ return getMetadata(chatId, "search_last_message_id"); }
    public void saveSearchCheckpoint(long chatId, long messageId){ setMetadata(chatId, "search_last_message_id", Long.toString(messageId)); }
    public void resetSearchCheckpoint(long chatId){ deleteMetadata(chatId, "search_last_message_id"); }

    public String loadMonitorCheckpoint(long chatId){ return getMetadata(chatId, "monitor_last_message_id"); }
    public void saveMonitorCheckpoint(long chatId, long messageId){ setMetadata(chatId, "monitor_last_message_id", Long.toString(messageId)); }
    public void resetMonitorCheckpoint(long chatId){ deleteMetadata(chatId, "monitor_last_message_id"); }
}
