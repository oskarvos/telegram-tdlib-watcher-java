/* ===========================================================
 *  DatabaseManager — управление SQLite-базами чатов
 *  - Раздельные файлы БД:
 *      DUMP   <название чата>.db — для дампа контента
 *      SEARCH <название чата>.db — только для результатов поиска
 *  - В SEARCH-БД создаём ТОЛЬКО таблицу search_results (+ служебные sqlite_*)
 *  - «Удаление» в блоке поиска = логическая очистка SEARCH-БД (DROP TABLE + VACUUM), файлы не трогаем
 * =========================================================== */
package com.oleg.td.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
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
import java.util.regex.Pattern;

@Component
public class DatabaseManager {
    /* ===================== Константы и зависимости ===================== */
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);

    /**
     * Каталог с файлами БД чатов: tdlib/db/
     */
    private final Path dbDir = Paths.get("tdlib", "db");

    private final ChatResolver chatResolver;

    /**
     * Паттерн для очистки имени файла
     */
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]");

    public DatabaseManager(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
        try {
            Files.createDirectories(dbDir);
        } catch (Exception e) {
            log.warn("БД: не удалось создать каталог {}: {}", dbDir, e.toString());
        }
    }

    /* ===================== Вспомогательные утилиты ===================== */

    /**
     * Квотирование идентификатора для SQL
     */
    private static String qIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /**
     * Заголовок чата по chatId для использования в имя файла
     */
    private String getChatNameForDatabase(long chatId) {
        try {
            String chatTitle = chatResolver.getChatTitle(chatId);
            if (chatTitle != null && !chatTitle.trim().isEmpty()) {
                return chatTitle.trim();
            }
        } catch (Exception e) {
            log.warn("БД: не удалось получить название чата {}: {}", chatId, e.getMessage());
        }
        // Fallback: если не удалось получить заголовок — используем ID
        return "chat_" + Math.abs(chatId);
    }

    /**
     * Очистка имени файла от недопустимых символов, ограничение длины
     */
    private String sanitizeFileName(String fileName, long chatId) {
        if (fileName == null || fileName.isEmpty()) return "unknown_chat";
        String sanitized = INVALID_FILENAME_CHARS.matcher(fileName).replaceAll("_").trim();
        while (sanitized.endsWith(".")) sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        if (sanitized.isEmpty()) return "chat_" + Math.abs(chatId);
        if (sanitized.length() > 100) sanitized = sanitized.substring(0, 100);
        return sanitized;
    }

    /* ===================== Пути/URL/соединения к БД ===================== */

    /**
     * Путь к БД ДАМПА: DUMP <chat>.db (оставляем как дефолт для обратной совместимости)
     */
    private Path dumpDbPath(long chatId) {
        String chatName = getChatNameForDatabase(chatId);
        String safe = sanitizeFileName("DUMP " + chatName, chatId) + ".db";
        return dbDir.resolve(safe);
    }

    /**
     * Путь к БД ПОИСКА: SEARCH <chat>.db
     */
    private Path searchDbPath(long chatId) {
        String chatName = getChatNameForDatabase(chatId);
        String safe = sanitizeFileName("SEARCH " + chatName, chatId) + ".db";
        return dbDir.resolve(safe);
    }

    /**
     * СТАРЫЙ метод — теперь возвращает путь ДАМП-БД (для уже существующих вызовов в проекте)
     */
    private Path dbPath(long chatId) {
        return dumpDbPath(chatId);
    }

    private String dbUrl(long chatId) {
        return "jdbc:sqlite:" + dbPath(chatId);
    }

    private String searchDbUrl(long chatId) {
        return "jdbc:sqlite:" + searchDbPath(chatId);
    }

    /**
     * Соединение с ДАМП-БД
     */
    private Connection open(long chatId) throws SQLException {
        return DriverManager.getConnection(dbUrl(chatId));
    }

    /**
     * Соединение с SEARCH-БД
     */
    private Connection openSearch(long chatId) throws SQLException {
        return DriverManager.getConnection(searchDbUrl(chatId));
    }

    /* ===================== Схемы БД ===================== */

    /**
     * Схема ДАМП-БД (DUMP): таблицы для сообщений/медиа/ссылок/метаданных/чекпоинтов.
     * ВНИМАНИЕ: здесь нет таблицы search_results — она живёт только в SEARCH-БД.
     */
    public void prepareSchema(long chatId) {
        final String tMessages = qIdent("messages");
        final String tPhotos = qIdent("photos");
        final String tVideos = qIdent("videos");
        final String tAudio = qIdent("audio");
        final String tDocuments = qIdent("documents");
        final String tLinks = qIdent("links");
        final String tMetadata = qIdent("metadata");
        final String tCheckpoints = qIdent("checkpoints");
        final String iLinks = qIdent("links_idx");

        final String createMessages = "CREATE TABLE IF NOT EXISTS " + tMessages + " (" +
                "id INTEGER PRIMARY KEY," +
                "date INTEGER," +
                "sender_id TEXT," +
                "reply_to INTEGER," +
                "text TEXT" +
                ")";
        final String createPhotos = "CREATE TABLE IF NOT EXISTS " + tPhotos + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT" +
                ")";
        final String createVideos = "CREATE TABLE IF NOT EXISTS " + tVideos + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "width INTEGER," +
                "height INTEGER," +
                "caption TEXT," +
                "file_path TEXT" +
                ")";
        final String createAudio = "CREATE TABLE IF NOT EXISTS " + tAudio + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "duration INTEGER," +
                "mime_type TEXT," +
                "file_path TEXT" +
                ")";
        final String createDocuments = "CREATE TABLE IF NOT EXISTS " + tDocuments + " (" +
                "message_id INTEGER PRIMARY KEY," +
                "file_id INTEGER," +
                "remote_id TEXT," +
                "file_name TEXT," +
                "mime_type TEXT," +
                "file_path TEXT" +
                ")";
        final String createLinks = "CREATE TABLE IF NOT EXISTS " + tLinks + " (" +
                "message_id INTEGER," +
                "url TEXT," +
                "context TEXT" +
                ")";
        final String createMetadata = "CREATE TABLE IF NOT EXISTS " + tMetadata + " (" +
                "key TEXT PRIMARY KEY," +
                "value TEXT" +
                ")";
        final String createCheckpoints = "CREATE TABLE IF NOT EXISTS " + tCheckpoints + " (" +
                "chat_id INTEGER PRIMARY KEY," +
                "last_message_id INTEGER NOT NULL DEFAULT 0," +
                "last_updated TEXT" +
                ")";
        final String idxLinks = "CREATE INDEX IF NOT EXISTS " + iLinks + " ON " + tLinks + "(url)";

        try (Connection c = open(chatId); Statement s = c.createStatement()) {
            s.execute(createMessages);
            s.execute(createPhotos);
            s.execute(createVideos);
            s.execute(createAudio);
            s.execute(createDocuments);
            s.execute(createLinks);
            s.execute(createMetadata);
            s.execute(createCheckpoints);
            s.execute(idxLinks);

            // защитное добавление file_path в медиа-таблицы (на случай миграций)
            addColumnIfMissing(c, "photos", "file_path", "TEXT");
            addColumnIfMissing(c, "videos", "file_path", "TEXT");
            addColumnIfMissing(c, "audio", "file_path", "TEXT");
            addColumnIfMissing(c, "documents", "file_path", "TEXT");

            log.info("БД 'DUMP {}' схема готова", getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы DUMP для '{}': {}", getChatNameForDatabase(chatId), e.getMessage(), e);
        }
    }

    /**
     * Схема SEARCH-БД (SEARCH): ТОЛЬКО таблица search_results (+ индексы).
     * Никаких других пользовательских таблиц.
     */
    public void prepareSearchSchema(long chatId) {
        final String tSearchResults = qIdent("search_results");
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

        // УНИКАЛЬНОСТЬ: message_id + keyword
        final String dedup = "DELETE FROM " + tSearchResults +
                " WHERE id NOT IN (SELECT MIN(id) FROM " + tSearchResults + " GROUP BY message_id, keyword)";
        final String uniqIdx = "CREATE UNIQUE INDEX IF NOT EXISTS search_msg_kw_unique ON " + tSearchResults + "(message_id, keyword)";

        try (Connection c = openSearch(chatId); Statement s = c.createStatement()) {
            s.execute(createSearchResults);
            s.execute(idxSearchKeyword);
            s.execute(idxSearchDate);

            // Разовая дедупликация (если вдруг уже были повторы)
            try { s.execute(dedup); } catch (SQLException ignore) {}

            // Гарантируем уникальность на будущее
            s.execute(uniqIdx);

            log.info("БД 'SEARCH {}' схема готова (search_results + unique(message_id, keyword))",
                    getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД: ошибка подготовки схемы SEARCH для '{}': {}", getChatNameForDatabase(chatId), e.getMessage(), e);
        }
    }


    /**
     * Добавляет столбец, если его нет (для миграций)
     */
    private void addColumnIfMissing(Connection c, String table, String col, String type) {
        try (PreparedStatement ps = c.prepareStatement("PRAGMA table_info(" + qIdent(table) + ")");
             ResultSet rs = ps.executeQuery()) {
            boolean exists = false;
            while (rs.next()) {
                if (col.equalsIgnoreCase(rs.getString("name"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                try (Statement s = c.createStatement()) {
                    s.execute("ALTER TABLE " + qIdent(table) + " ADD COLUMN " + col + " " + type);
                    log.info("БД: для {} добавлен столбец {}", table, col);
                }
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить/добавить столбец {} в {}: {}", col, table, e.getMessage());
        }
    }

    /* ===================== Инкрементальные хелперы (DUMP) ===================== */

    /**
     * MAX(id) из messages; если строк нет — 0.
     */
    public long getLastSavedMessageId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(id),0) FROM " + qIdent("messages");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * MAX(message_id) по медиа-таблицам (на случай если сообщения не сохраняли).
     */
    public long getMaxMediaId(long chatId) {
        long max = 0;
        try (Connection c = open(chatId); Statement s = c.createStatement()) {
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("photos")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("videos")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("audio")));
            max = Math.max(max, scalarLong(s, "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("documents")));
        } catch (SQLException ignored) {
        }
        return max;
    }

    private long scalarLong(Statement s, String sql) {
        try (ResultSet rs = s.executeQuery(sql)) {
            if (rs.next()) return rs.getLong(1);
        } catch (SQLException ignored) {
        }
        return 0L;
    }

    /* ===================== Upsert-операции (DUMP) ===================== */

    /**
     * Сохранение сообщения
     */
    public void saveMessage(long chatId, long messageId, long date, String senderId, Long replyTo, String text) {
        final String sql = "INSERT OR IGNORE INTO " + qIdent("messages") +
                "(id, date, sender_id, reply_to, text) VALUES(?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setLong(2, date);
            st.setString(3, senderId);
            if (replyTo == null) st.setNull(4, Types.INTEGER);
            else st.setLong(4, replyTo);
            st.setString(5, text);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения сообщения {} для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /**
     * Сохранение фото
     */
    public void savePhoto(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer w, Integer h, String caption, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("photos") +
                "(message_id, file_id, remote_id, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (w == null) st.setNull(4, Types.INTEGER);
            else st.setInt(4, w);
            if (h == null) st.setNull(5, Types.INTEGER);
            else st.setInt(5, h);
            st.setString(6, caption);
            st.setString(7, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения фото (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /**
     * Сохранение видео
     */
    public void saveVideo(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer duration, Integer w, Integer h, String caption, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("videos") +
                "(message_id, file_id, remote_id, duration, width, height, caption, file_path) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER);
            else st.setInt(4, duration);
            if (w == null) st.setNull(5, Types.INTEGER);
            else st.setInt(5, w);
            if (h == null) st.setNull(6, Types.INTEGER);
            else st.setInt(6, h);
            st.setString(7, caption);
            st.setString(8, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения видео (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /**
     * Сохранение аудио
     */
    public void saveAudio(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer duration, String mime, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("audio") +
                "(message_id, file_id, remote_id, duration, mime_type, file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            if (duration == null) st.setNull(4, Types.INTEGER);
            else st.setInt(4, duration);
            st.setString(5, mime);
            st.setString(6, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения аудио (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /**
     * Сохранение документа
     */
    public void saveDocument(long chatId, long messageId, Integer fileId, String remoteId,
                             String fileName, String mimeType, String filePath) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("documents") +
                "(message_id, file_id, remote_id, file_name, mime_type, file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            if (fileId == null) st.setNull(2, Types.INTEGER);
            else st.setInt(2, fileId);
            st.setString(3, remoteId);
            st.setString(4, fileName);
            st.setString(5, mimeType);
            st.setString(6, filePath);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения документа (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /**
     * Сохранение ссылки
     */
    public void saveLink(long chatId, long messageId, String urlStr, String context) {
        final String sql = "INSERT INTO " + qIdent("links") + "(message_id, url, context) VALUES(?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setString(2, urlStr);
            st.setString(3, context);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения ссылки (msg_id={}) для чата {}: {}", messageId, chatId, e.getMessage(), e);
        }
    }

    /**
     * Сохранение/загрузка метаданных (DUMP-БД)
     */
    public void saveMetadata(long chatId, String key, String value) {
        final String sql = "INSERT OR REPLACE INTO " + qIdent("metadata") + "(key, value) VALUES(?, ?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, key);
            st.setString(2, value);
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения метаданных для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    public String loadMetadata(long chatId, String key) {
        final String sql = "SELECT value FROM " + qIdent("metadata") + " WHERE key = ?";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setString(1, key);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            log.error("БД: ошибка загрузки метаданных для чата {}: {}", chatId, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Контрольные точки (DUMP-БД)
     */
    public void saveCheckpoint(long chatId, long lastMessageId) {
        final String sql = "REPLACE INTO " + qIdent("checkpoints") + "(chat_id, last_message_id, last_updated) VALUES(?,?,?)";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, chatId);
            st.setLong(2, lastMessageId);
            st.setString(3, LocalDateTime.now().toString());
            st.executeUpdate();
            log.debug("БД: сохранена контрольная точка для чата {}: last_message_id={}", chatId, lastMessageId);
        } catch (SQLException e) {
            log.error("БД: ошибка сохранения контрольной точки для чата {}: {}", chatId, e.getMessage(), e);
        }
    }

    public long loadCheckpoint(long chatId) {
        final String sql = "SELECT last_message_id FROM " + qIdent("checkpoints") + " WHERE chat_id = ?";
        try (Connection c = open(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, chatId);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getLong("last_message_id");
            }
        } catch (SQLException e) {
            log.error("БД: ошибка загрузки контрольной точки для чата {}: {}", chatId, e.getMessage(), e);
        }
        return 0L;
    }

    /* ===================== Операции с результатами поиска (SEARCH) ===================== */

    /**
     * Вставка результата поиска в SEARCH-БД
     */
    public void saveSearchResultSearchDb(long chatId, long messageId, LocalDateTime messageDate,
                                         String keyword, String messageText, String senderId, String senderName) {
        final String sql = "INSERT OR IGNORE INTO " + qIdent("search_results") +
                "(message_id, message_date, keyword, message_text, sender_id, sender_name, found_date) " +
                "VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openSearch(chatId); PreparedStatement st = c.prepareStatement(sql)) {
            st.setLong(1, messageId);
            st.setString(2, messageDate.toString());
            st.setString(3, keyword);
            st.setString(4, messageText);
            st.setString(5, senderId);
            st.setString(6, senderName);
            st.setString(7, LocalDateTime.now().toString());
            st.executeUpdate();
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка сохранения результата поиска: {}", e.getMessage(), e);
        }
    }

    /**
     * Кол-во результатов в SEARCH-БД
     */
    public int getSearchResultsCountInSearchDb(long chatId) {
        final String sql = "SELECT COUNT(*) FROM " + qIdent("search_results");
        try (Connection c = openSearch(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка получения количества результатов поиска: {}", e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Чтение результатов из SEARCH-БД
     */
    public List<SearchResult> getSearchResultsFromSearchDb(long chatId) {
        final String sql = "SELECT * FROM " + qIdent("search_results") + " ORDER BY found_date DESC";
        List<SearchResult> results = new ArrayList<>();
        try (Connection c = openSearch(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                SearchResult r = new SearchResult();
                r.setId(rs.getLong("id"));
                r.setChatId(chatId);
                r.setChatTitle(getChatNameForDatabase(chatId));
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

    /* ===================== Очистка SEARCH-БД (кнопка «Удалить базу» в блоке поиска) ===================== */

    /**
     * Логическая очистка всех SEARCH-БД:
     * - проходим по файлам "SEARCH *.db"
     * - дропаем ВСЕ пользовательские таблицы (в т.ч. search_results)
     * - выполняем VACUUM
     * - файлы не удаляем
     */
    /** Полная логическая очистка всех SEARCH-БД + сброс чекпоинтов поиска во всех DUMP-БД */
    public void clearSearchChatDatabases() {
        // 1) Очистка всех SEARCH *.db
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "SEARCH *.db")) {
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
            log.error("БД: ошибка перебора каталога {}: {}", dbDir, e.getMessage(), e);
        }

        // 2) Сброс ключа search_last_message_id во всех DUMP *.db
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "DUMP *.db")) {
            for (Path p : ds) {
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p)) {
                    if (tableExists(c, "metadata")) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "DELETE FROM " + qIdent("metadata") + " WHERE key = ?")) {
                            ps.setString(1, "search_last_message_id");
                            ps.executeUpdate();
                        }
                        log.info("DUMP-БД {}: сброшен search_last_message_id", p.getFileName());
                    }
                } catch (SQLException e) {
                    log.error("БД(DUMP): ошибка сброса чекпоинта в {}: {}", p.getFileName(), e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            log.error("БД: ошибка перебора каталога {}: {}", dbDir, e.getMessage(), e);
        }
    }


    /* ===================== Служебные хелперы для БД ===================== */

    /**
     * Проверка наличия таблицы
     */
    private boolean tableExists(Connection c, String table) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.warn("БД: не удалось проверить наличие таблицы {}: {}", table, e.getMessage());
            return false;
        }
    }

    /**
     * Количество строк в таблице (0, если таблицы нет/ошибка)
     */
    private long tableCount(Connection c, String table) {
        if (!tableExists(c, table)) return 0L;
        String sql = "SELECT COUNT(*) FROM " + qIdent(table);
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.warn("БД: не удалось получить COUNT(*) из {}: {}", table, e.getMessage());
            return 0L;
        }
    }

    /**
     * Дропает все пользовательские таблицы в БД.
     * Системные таблицы SQLite (sqlite_sequence) не трогаем.
     */
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
                s.execute("DROP TABLE IF EXISTS " + qIdent(t));
            }
        }
    }

    /* ===================== (Не используется сейчас) Удаление файла БД ===================== */

    /**
     * Физическое удаление файла SEARCH-БД конкретного чата (не используется кнопкой)
     */
    public void deleteSearchDatabaseFile(long chatId) {
        try {
            Path path = searchDbPath(chatId);
            Files.deleteIfExists(path);
            log.info("Файл SEARCH-БД удалён: {}", path);
        } catch (IOException e) {
            log.error("БД: ошибка удаления файла SEARCH-БД: {}", e.getMessage(), e);
        }
    }

    /**
     * MAX(message_id) из photos; если строк нет — 0.
     */
    public long getLastSavedPhotoId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("photos");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * MAX(message_id) из videos; если строк нет — 0.
     */
    public long getLastSavedVideoId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("videos");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * MAX(message_id) из audio; если строк нет — 0.
     */
    public long getLastSavedAudioId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("audio");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * MAX(message_id) из documents; если строк нет — 0.
     */
    public long getLastSavedDocumentId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("documents");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * MAX(message_id) из links; если строк нет — 0.
     */
    public long getLastSavedLinkId(long chatId) {
        final String sql = "SELECT COALESCE(MAX(message_id),0) FROM " + qIdent("links");
        try (Connection c = open(chatId); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            return 0L;
        }
    }

    /**
     * Совместимость: старое имя метода из контроллера
     */
    public void clearAllSearchDatabases() {
        clearSearchChatDatabases();
    }

    /** Сброс чекпоинта поиска (ключ search_last_message_id) в DUMP-БД конкретного чата */
    public void resetSearchCheckpoint(long chatId) {
        try (Connection c = open(chatId)) {
            if (tableExists(c, "metadata")) {
                try (PreparedStatement ps =
                             c.prepareStatement("DELETE FROM " + qIdent("metadata") + " WHERE key = ?")) {
                    ps.setString(1, "search_last_message_id");
                    ps.executeUpdate();
                }
            }
            log.info("БД(DUMP {}): сброшен чекпоинт поиска search_last_message_id",
                    getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД(DUMP): ошибка сброса чекпоинта поиска для {}: {}", chatId, e.getMessage(), e);
        }
    }

    /** Очистить SEARCH-БД конкретного чата (дроп всех пользовательских таблиц + VACUUM) */
    public void clearSearchDatabase(long chatId) {
        try (Connection c = openSearch(chatId)) {
            dropAllUserTables(c);
            try (Statement s = c.createStatement()) { s.execute("VACUUM"); }
            log.info("Очищена SEARCH-БД для чата {}", getChatNameForDatabase(chatId));
        } catch (SQLException e) {
            log.error("БД(SEARCH): ошибка очистки для чата {}: {}", chatId, e.getMessage(), e);
        }
    }
}
