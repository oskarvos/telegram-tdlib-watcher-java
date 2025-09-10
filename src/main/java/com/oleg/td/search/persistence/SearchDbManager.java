package com.oleg.td.search.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.search.model.SearchResult;
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
public class SearchDbManager {
    private static final Logger log = LoggerFactory.getLogger(SearchDbManager.class);

    private final Path dbDir = Paths.get("tdlib", "db");
    private final ChatResolver chatResolver;

    public SearchDbManager(ChatResolver chatResolver) {
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
    private Path searchDbPath(long chatId){
        String fn = safe("SEARCH " + chatName(chatId), chatId) + ".db";
        return dbDir.resolve(fn);
    }

    private Connection openDump(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dumpDbPath(chatId));
    }
    private Connection openSearch(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + searchDbPath(chatId));
    }

    // --- DUMP metadata (чекпоинт поиска хранится в DUMP)
    public void ensureDumpMetadata(long chatId){
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e){
            log.error("DUMP {}: cannot ensure metadata: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    public String loadSearchCheckpoint(long chatId){
        final String sql = "SELECT value FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "search_last_message_id");
            try (ResultSet rs = ps.executeQuery()){
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e){ log.warn("load search checkpoint err: {}", e.getMessage()); }
        return null;
    }

    public void saveSearchCheckpoint(long chatId, long messageId){
        final String sql = "INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "search_last_message_id");
            ps.setString(2, Long.toString(messageId));
            ps.executeUpdate();
        } catch (SQLException e){ log.error("save search checkpoint err: {}", e.getMessage(), e); }
    }

    public void resetSearchCheckpoint(long chatId){
        final String sql = "DELETE FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "search_last_message_id");
            ps.executeUpdate();
        } catch (SQLException e){ log.error("reset search checkpoint err: {}", e.getMessage(), e); }
    }

    // --- SEARCH schema (единственная пользовательская таблица)
    public void prepareSearchSchema(long chatId){
        final String t = q("search_results");
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
        final String idx1 = "CREATE INDEX IF NOT EXISTS search_keyword_idx ON " + t + "(keyword)";
        final String idx2 = "CREATE INDEX IF NOT EXISTS search_date_idx ON " + t + "(message_date)";
        try (Connection c = openSearch(chatId); Statement s = c.createStatement()){
            s.execute(create); s.execute(idx1); s.execute(idx2);
        } catch (SQLException e){
            log.error("SEARCH {}: schema error: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    public void saveSearchHit(long chatId, long messageId, LocalDateTime messageDate,
                              String keyword, String messageText, String senderId, String senderName){
        final String sql = "INSERT INTO " + q("search_results") +
                "(message_id,message_date,keyword,message_text,sender_id,sender_name,found_date) " +
                "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openSearch(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            ps.setString(2, messageDate.toString());
            ps.setString(3, keyword);
            ps.setString(4, messageText);
            ps.setString(5, senderId);
            ps.setString(6, senderName);
            ps.setString(7, LocalDateTime.now().toString());
            ps.executeUpdate();
        } catch (SQLException e){
            log.error("SEARCH save hit err: {}", e.getMessage(), e);
        }
    }

    // — «Удаление» в поиске: логическая очистка всех SEARCH-*.db + сброс чекпоинтов в DUMP
    public void clearSearchChatDatabases(){
        // 1) очистка SEARCH db (DROP TABLE + VACUUM)
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "SEARCH *.db")) {
            for (Path p : ds){
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    dropAllUserTables(c);
                    try (Statement s = c.createStatement()){ s.execute("VACUUM"); }
                } catch (SQLException e){
                    log.error("clear SEARCH {} err: {}", p.getFileName(), e.getMessage(), e);
                }
            }
        } catch (Exception e){ log.error("list SEARCH*.db err: {}", e.getMessage(), e); }

        // 2) сброс чекпоинтов поиска в каждом DUMP
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "DUMP *.db")) {
            for (Path p : ds){
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    ensureTable(c, "metadata");
                    try (PreparedStatement ps =
                                 c.prepareStatement("DELETE FROM " + q("metadata") + " WHERE key=?")){
                        ps.setString(1, "search_last_message_id");
                        ps.executeUpdate();
                    }
                } catch (SQLException e){
                    log.error("reset search checkpoint in {} err: {}", p.getFileName(), e.getMessage(), e);
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

    public List<SearchResult> getSearchResults(long chatId, int limit, int offset){
        final String sql = "SELECT rowid AS id, message_id, message_date, keyword, message_text, " +
                "sender_id, sender_name, found_date FROM " + q("search_results") +
                " ORDER BY found_date DESC LIMIT ? OFFSET ?";
        List<SearchResult> list = new ArrayList<>();
        try (Connection c = openSearch(chatId); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchResult r = new SearchResult();
                    r.setId(rs.getLong("id"));
                    r.setChatId(chatId);
                    r.setChatTitle(chatName(chatId));
                    r.setMessageId(rs.getLong("message_id"));
                    r.setMessageDate(LocalDateTime.parse(rs.getString("message_date")));
                    r.setKeyword(rs.getString("keyword"));
                    r.setMessageText(rs.getString("message_text"));
                    r.setSenderId(rs.getString("sender_id"));
                    r.setSenderName(rs.getString("sender_name"));
                    r.setFoundDate(LocalDateTime.parse(rs.getString("found_date")));
                    list.add(r);
                }
            }
        } catch (SQLException e){
            log.error("SEARCH get results err: {}", e.getMessage(), e);
        }
        return list;
    }
}
