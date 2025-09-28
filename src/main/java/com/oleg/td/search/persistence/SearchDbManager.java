package com.oleg.td.search.persistence;

import com.oleg.td.search.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class SearchDbManager {
    private static final Logger log = LoggerFactory.getLogger(SearchDbManager.class);

    private final Path dbDir = Paths.get("tdlib", "db");

    public SearchDbManager() {
        try {
            Files.createDirectories(dbDir);
        } catch (Exception ignored) {
        }
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private Path dumpDbPath(long chatId) {
        return dbDir.resolve("DUMP_" + chatId + ".db");
    }

    private Path searchDbPath(long chatId) {
        return dbDir.resolve("SEARCH_" + chatId + ".db");
    }

    private Connection openDump(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dumpDbPath(chatId));
    }

    private Connection openSearch(long chatId) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + searchDbPath(chatId));
    }

    // --- DUMP metadata
    public void ensureDumpMetadata(long chatId) {
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e) {
            log.error("DUMP {}: cannot ensure metadata: {}", chatId, e.getMessage(), e);
        }
    }

    public String loadSearchCheckpoint(long chatId) {
        final String sql = "SELECT value FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "search_last_message_id");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            log.warn("load search checkpoint err: {}", e.getMessage());
        }
        return null;
    }

    public void saveSearchCheckpoint(long chatId, long messageId) {
        final String sql = "INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "search_last_message_id");
            ps.setString(2, Long.toString(messageId));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("save search checkpoint err: {}", e.getMessage(), e);
        }
    }

    public void resetSearchCheckpoint(long chatId) {
        final String sql = "DELETE FROM " + q("metadata") + " WHERE key=?";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "search_last_message_id");
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("reset search checkpoint err: {}", e.getMessage(), e);
        }
    }

    // --- SEARCH schema
    public void prepareSearchSchema(long chatId) {
        final String t = q("search_results");
        final String create = "CREATE TABLE IF NOT EXISTS " + t + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "message_id INTEGER," +
                "message_date TEXT," +
                "keyword TEXT," +
                "message_text TEXT," +
                "sender_id TEXT," +
                "sender_name TEXT," +
                "found_date TEXT," +
                "UNIQUE(message_id, keyword) ON CONFLICT IGNORE" +
                ")";
        try (Connection c = openSearch(chatId); Statement s = c.createStatement()) {
            s.execute(create);
            s.execute("CREATE INDEX IF NOT EXISTS search_keyword_idx ON " + t + "(keyword)");
            s.execute("CREATE INDEX IF NOT EXISTS search_date_idx ON " + t + "(message_date)");
        } catch (SQLException e) {
            log.error("SEARCH {}: schema error: {}", chatId, e.getMessage(), e);
        }
    }

    public boolean saveSearchResult(long chatId, long messageId, LocalDateTime messageDate,
                                    String keyword, String messageText, String senderId, String senderName) {
        final String sql = "INSERT OR IGNORE INTO " + q("search_results") +
                "(message_id,message_date,keyword,message_text,sender_id,sender_name,found_date) " +
                "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openSearch(chatId); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, messageId);
            ps.setString(2, messageDate.toString());
            ps.setString(3, keyword);
            ps.setString(4, messageText);
            ps.setString(5, senderId);
            ps.setString(6, senderName);
            ps.setString(7, LocalDateTime.now().toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("SEARCH {} save result err: {}", chatId, e.getMessage(), e);
            return false;
        }
    }

    public void clearSearchDatabase(long chatId) {
        Path p = searchDbPath(chatId);
        deleteDbWithSidecars(p);
        log.info("Удалён файл SEARCH БД: {}", p.getFileName());
    }

    public void clearSearchChatDatabases() {
        try {
            Files.createDirectories(dbDir);
        } catch (Exception ignore) {
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "SEARCH_*.db")) {
            for (Path dbFile : ds) {
                deleteDbWithSidecars(dbFile);
                log.info("Удалён файл SEARCH БД: {}", dbFile.getFileName());
            }
        } catch (Exception e) {
            log.error("list SEARCH*.db err: {}", e.getMessage(), e);
        }
    }

    private void deleteDbWithSidecars(Path dbFile) {
        try { Files.deleteIfExists(dbFile); } catch (IOException ignore) {}
        try { Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName().toString() + "-wal")); } catch (IOException ignore) {}
        try { Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName().toString() + "-shm")); } catch (IOException ignore) {}
    }

    public List<SearchResult> getSearchResults(long chatId, int limit, int offset) {
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
                    r.setChatTitle("chat_" + chatId);
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
        } catch (SQLException e) {
            log.error("SEARCH {} get results err: {}", chatId, e.getMessage(), e);
        }
        return list;
    }
}
