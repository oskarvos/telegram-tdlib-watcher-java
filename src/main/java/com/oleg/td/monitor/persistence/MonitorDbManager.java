package com.oleg.td.monitor.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class MonitorDbManager {
    private static final Logger log = LoggerFactory.getLogger(MonitorDbManager.class);

    private final Path dbDir = Paths.get("tdlib", "db");
    private final ChatResolver chatResolver;

    public MonitorDbManager(ChatResolver chatResolver) {
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

    private Path dumpDbPath(long chatId){
        String fn = safe("DUMP " + chatName(chatId), chatId) + ".db";
        return dbDir.resolve(fn);
    }
    private Path monitorDbPath(long chatId){
        String fn = safe("MONITOR " + chatName(chatId), chatId) + ".db";
        return dbDir.resolve(fn);
    }

    private Connection openDump(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dumpDbPath(chatId));
    }
    private Connection openMonitor(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + monitorDbPath(chatId));
    }

    // --- DUMP metadata
    public void ensureDumpMetadata(long chatId){
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e){
            log.error("DUMP {}: cannot ensure metadata: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    public String loadMonitorCheckpoint(long chatId){
        final String sql = "SELECT value FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "monitor_last_message_id");
            try (ResultSet rs = ps.executeQuery()){
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e){ log.warn("load checkpoint err: {}", e.getMessage()); }
        return null;
    }

    public void saveMonitorCheckpoint(long chatId, long messageId){
        final String sql = "INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "monitor_last_message_id");
            ps.setString(2, Long.toString(messageId));
            ps.executeUpdate();
        } catch (SQLException e){ log.error("save checkpoint err: {}", e.getMessage(), e); }
    }

    public void resetMonitorCheckpoint(long chatId){
        final String sql = "DELETE FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "monitor_last_message_id");
            ps.executeUpdate();
        } catch (SQLException e){ log.error("reset checkpoint err: {}", e.getMessage(), e); }
    }

    // --- MONITOR schema
    public void prepareMonitorSchema(long chatId){
        final String t = q("monitor_results");
        final String create = "CREATE TABLE IF NOT EXISTS " + t + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "message_id INTEGER," +
                "message_date TEXT," +
                "keyword TEXT," +
                "message_text TEXT," +
                "sender_id TEXT," +
                "sender_name TEXT," +
                "found_date TEXT" +
                ")";
        final String idx1 = "CREATE INDEX IF NOT EXISTS monitor_keyword_idx ON " + t + "(keyword)";
        final String idx2 = "CREATE INDEX IF NOT EXISTS monitor_date_idx ON " + t + "(message_date)";
        try (Connection c = openMonitor(chatId); Statement s = c.createStatement()){
            s.execute(create); s.execute(idx1); s.execute(idx2);
        } catch (SQLException e){
            log.error("MONITOR {}: schema error: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    public void saveMonitorHit(long chatId, long messageId, LocalDateTime messageDate,
                               String keyword, String messageText, String senderId, String senderName){
        final String sql = "INSERT INTO " + q("monitor_results") +
                "(message_id,message_date,keyword,message_text,sender_id,sender_name,found_date) " +
                "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openMonitor(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            ps.setString(2, messageDate.toString());
            ps.setString(3, keyword);
            ps.setString(4, messageText);
            ps.setString(5, senderId);
            ps.setString(6, senderName);
            ps.setString(7, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e){
            log.error("MONITOR save hit err: {}", e.getMessage(), e);
        }
    }

    // --- clear all MONITOR *.db + reset checkpoints in all DUMP *.db
    public void clearMonitorChatDatabases(){
        // 1) clear MONITOR db files
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "MONITOR *.db")) {
            for (Path p : ds){
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    dropAllUserTables(c);
                    try (Statement s = c.createStatement()){ s.execute("VACUUM"); }
                } catch (SQLException e){
                    log.error("clear MONITOR {} err: {}", p.getFileName(), e.getMessage(), e);
                }
            }
        } catch (Exception e){ log.error("list MONITOR*.db err: {}", e.getMessage(), e); }

        // 2) reset checkpoints in every DUMP
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "DUMP *.db")) {
            for (Path p : ds){
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    ensureTable(c, "metadata");
                    try (PreparedStatement ps =
                                 c.prepareStatement("DELETE FROM " + q("metadata") + " WHERE key=?")){
                        ps.setString(1, "monitor_last_message_id");
                        ps.executeUpdate();
                    }
                } catch (SQLException e){
                    log.error("reset checkpoint in {} err: {}", p.getFileName(), e.getMessage(), e);
                }
            }
        } catch (Exception e){ log.error("list DUMP*.db err: {}", e.getMessage(), e); }
    }

    // --- helpers
    private void ensureTable(Connection c, String name) throws SQLException {
        try (Statement s = c.createStatement()){
            s.execute("CREATE TABLE IF NOT EXISTS " + q(name) + " (key TEXT PRIMARY KEY, value TEXT)");
        }
    }
    private void dropAllUserTables(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'");
             ResultSet rs = ps.executeQuery()){
            while (rs.next()){
                String n = rs.getString(1);
                if (!"sqlite_sequence".equalsIgnoreCase(n)) tables.add(n);
            }
        }
        try (Statement s = c.createStatement()){
            for (String t : tables) s.execute("DROP TABLE IF EXISTS " + q(t));
        }
    }

    public java.util.List<com.oleg.td.monitor.model.MonitorHit> getMonitorResults(long chatId, int limit, int offset){
        final String sql = "SELECT rowid AS id, message_id, message_date, keyword, message_text, " +
                "sender_id, sender_name, found_date FROM " + q("monitor_results") +
                " ORDER BY found_date DESC LIMIT ? OFFSET ?";
        java.util.List<com.oleg.td.monitor.model.MonitorHit> list = new java.util.ArrayList<>();
        try (Connection c = openMonitor(chatId); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    com.oleg.td.monitor.model.MonitorHit h = new com.oleg.td.monitor.model.MonitorHit();
                    h.setId(rs.getLong("id"));
                    h.setChatId(chatId);
                    h.setChatTitle(chatName(chatId));
                    h.setMessageId(rs.getLong("message_id"));
                    h.setMessageDate(java.time.LocalDateTime.parse(rs.getString("message_date")));
                    h.setKeyword(rs.getString("keyword"));
                    h.setMessageText(rs.getString("message_text"));
                    h.setSenderId(rs.getString("sender_id"));
                    h.setSenderName(rs.getString("sender_name"));
                    h.setFoundDate(java.time.LocalDateTime.parse(rs.getString("found_date")));
                    list.add(h);
                }
            }
        } catch (SQLException e){
            log.error("MONITOR get results err: {}", e.getMessage(), e);
        }
        return list;
    }
}
