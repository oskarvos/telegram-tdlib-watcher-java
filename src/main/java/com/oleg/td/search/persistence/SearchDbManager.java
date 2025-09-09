package com.oleg.td.search.persistence;

import com.oleg.td.common.DbUtils;
import com.oleg.td.search.model.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class SearchDbManager {
    private static final Logger log = LoggerFactory.getLogger(SearchDbManager.class);
    private final DbUtils dbUtils;

    public SearchDbManager(DbUtils dbUtils) {
        this.dbUtils = dbUtils;
    }

    private Path searchDbPath(long chatId) {
        String chatName = dbUtils.getChatNameForDatabase(chatId);
        String safe = dbUtils.sanitizeFileName("SEARCH " + chatName, chatId) + ".db";
        return dbUtils.getDbDir().resolve(safe);
    }

    private String searchDbUrl(long chatId) {
        return "jdbc:sqlite:" + searchDbPath(chatId);
    }

    private Connection openSearch(long chatId) throws SQLException {
        return DriverManager.getConnection(searchDbUrl(chatId));
    }

    public void prepareSearchSchema(long chatId) {
        final String tSearchResults = dbUtils.qIdent("search_results");
        final String createSearchResults = "CREATE TABLE IF NOT EXISTS " + tSearchResults + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "message_id INTEGER," +
                "message_date TEXT," +
                "keyword TEXT," +
                "message_text TEXT," +
                "sender_id TEXT," +
                "sender_name TEXT," +
                "found_date TEXT" +
                ")";
        final String idxSearchKeyword = "CREATE INDEX IF NOT EXISTS search_keyword_idx ON " + tSearchResults + "(keyword)";
        final String idxSearchDate = "CREATE INDEX IF NOT EXISTS search_date_idx ON " + tSearchResults + "(message_date)";

        final String dedup = "DELETE FROM " + tSearchResults +
                " WHERE id NOT IN (SELECT MIN(id) FROM " + tSearchResults + " GROUP BY message_id, keyword)";
        final String uniqIdx = "CREATE UNIQUE INDEX IF NOT EXISTS search_msg_kw_unique ON " + tSearchResults + "(message_id, keyword)";

        try (Connection c = openSearch(chatId); Statement s = c.createStatement()) {
            s.execute(createSearchResults);
            s.execute(idxSearchKeyword);
            s.execute(idxSearchDate);
            try { s.execute(dedup); } catch (SQLException ignore) {}
            s.execute(uniqIdx);
            log.info("БД 'SEARCH {}' схема готова", dbUtils.getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы SEARCH для '{}': {}", dbUtils.getChatNameForDatabase(chatId), e.getMessage(), e);
        }
    }

    public boolean saveSearchResult(long chatId, long messageId, LocalDateTime messageDate,
                                    String keyword, String messageText, String senderId, String senderName) {
        final String sql = "INSERT OR IGNORE INTO " + dbUtils.qIdent("search_results") +
                "(message_id, message_date, keyword, message_text, sender_id, sender_name, found_date) " +
                "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openSearch(chatId)) {
            try (PreparedStatement chk = c.prepareStatement(
                    "SELECT 1 FROM " + dbUtils.qIdent("search_results") + " WHERE message_id = ? LIMIT 1")) {
                chk.setLong(1, messageId);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }

            try (PreparedStatement st = c.prepareStatement(sql)) {
                st.setLong(1, messageId);
                st.setString(2, messageDate.toString());
                st.setString(3, keyword);
                st.setString(4, messageText);
                st.setString(5, senderId);
                st.setString(6, senderName);
                st.setString(7, LocalDateTime.now().toString());
                int affected = st.executeUpdate();
                return affected > 0;
            }
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка сохранения результата поиска: {}", e.getMessage(), e);
            return false;
        }
    }

    public int getSearchResultsCount(long chatId) {
        final String sql = "SELECT COUNT(*) FROM " + dbUtils.qIdent("search_results");
        try (Connection c = openSearch(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка получения количества результатов поиска: {}", e.getMessage(), e);
            return 0;
        }
    }

    public List<SearchResult> getSearchResults(long chatId) {
        final String sql = "SELECT * FROM " + dbUtils.qIdent("search_results") + " ORDER BY found_date DESC";
        List<SearchResult> results = new ArrayList<>();
        try (Connection c = openSearch(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                SearchResult r = new SearchResult();
                r.setId(rs.getLong("id"));
                r.setChatId(chatId);
                r.setChatTitle(dbUtils.getChatNameForDatabase(chatId));
                r.setMessageId(rs.getLong("message_id"));
                r.setMessageDate(LocalDateTime.parse(rs.getString("message_date")));
                r.setKeyword(rs.getString("keyword"));
                r.setMessageText(rs.getString("message_text"));
                r.setSenderId(rs.getString("sender_id"));
                r.setSenderName(rs.getString("sender_name"));
                r.setFoundDate(LocalDateTime.parse(rs.getString("found_date")));
                results.add(r);
            }
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка получения результатов поиска: {}", e.getMessage(), e);
        }
        return results;
    }

    public void clearSearchDatabase(long chatId) {
        try (Connection c = openSearch(chatId)) {
            dropAllUserTables(c);
            try (Statement s = c.createStatement()) { s.execute("VACUUM"); }
            log.info("Очищена SEARCH-БД для чата {}", dbUtils.getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка очистки для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    public void clearAllSearchDatabases() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbUtils.getDbDir(), "SEARCH *.db")) {
            for (Path p : ds) {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    dropAllUserTables(c);
                    try (Statement s = c.createStatement()) { s.execute("VACUUM"); }
                    log.info("Очищена SEARCH-БД: {}", p.getFileName());
                } catch (SQLException e) {
                    log.error("БД(SEARCH): ошибка обработки {}: {}", p, e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("БД: ошибка перебора каталога {}: {}", dbUtils.getDbDir(), e.getMessage(), e);
        }
    }

    private void dropAllUserTables(Connection c) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM sqlite_master WHERE type='table'")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    if (!"sqlite_sequence".equalsIgnoreCase(name)) {
                        tables.add(name);
                    }
                }
            }
        }
        try (Statement s = c.createStatement()) {
            for (String t : tables) {
                s.execute("DROP TABLE IF EXISTS " + dbUtils.qIdent(t));
            }
        }
    }
}