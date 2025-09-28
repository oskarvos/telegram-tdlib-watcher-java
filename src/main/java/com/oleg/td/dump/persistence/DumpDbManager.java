package com.oleg.td.dump.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.regex.Pattern;

/** Менеджер DUMP-БД: фабрика сессий, схема, утилиты путей/очистки.
 * (Сама работа с INSERT/UPDATE лежит в DumpDbSession-реализации.) */
@Component
public class DumpDbManager {

    private static final Logger log = LoggerFactory.getLogger(DumpDbManager.class);

    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|]");

    private final Path dbDir    = Paths.get("tdlib", "db");
    private final Path filesDir = Paths.get("tdlib", "files");
    private final ChatResolver chatResolver;

    public DumpDbManager(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
        try { Files.createDirectories(dbDir); }   catch (Exception ignore) {}
        try { Files.createDirectories(filesDir);} catch (Exception ignore) {}
    }

    // ===== фабрика сессий =====
    public DumpDbSession openSession(long chatId) throws SQLException {
        Connection c = openDump(chatId);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
            s.execute("PRAGMA temp_store=MEMORY");
            s.execute("PRAGMA busy_timeout=5000");
        }
        c.setAutoCommit(false);
        return new SQLiteDumpDbSession(chatId, c);
    }

    // ===== схема =====
    public void prepareSchema(long chatId) {
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "date INTEGER," +
                    "sender_id TEXT," +
                    "reply_to INTEGER," +
                    "text TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_messages_mid ON messages(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS photos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "width INTEGER," +
                    "height INTEGER," +
                    "caption TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_photos_mid ON photos(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS videos (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "duration INTEGER," +
                    "width INTEGER," +
                    "height INTEGER," +
                    "caption TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_videos_mid ON videos(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS audio (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "duration INTEGER," +
                    "mime TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_audio_mid ON audio(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS documents (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "file_id INTEGER," +
                    "remote_id TEXT," +
                    "file_name TEXT," +
                    "mime_type TEXT," +
                    "file_path TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_documents_mid ON documents(message_id)");

            s.execute("CREATE TABLE IF NOT EXISTS links (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "message_id INTEGER," +
                    "url TEXT," +
                    "context TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_links_mid ON links(message_id)");

            ensureDumpMetadata(chatId);

            dedupTable(c, "messages",  "message_id");
            dedupTable(c, "photos",    "message_id");
            dedupTable(c, "videos",    "message_id");
            dedupTable(c, "audio",     "message_id");
            dedupTable(c, "documents", "message_id");
            dedupTable(c, "links",     "message_id, url");

            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_messages_mid  ON messages(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_photos_mid    ON photos(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_videos_mid    ON videos(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_audio_mid     ON audio(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_documents_mid ON documents(message_id)");
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_links_mid_url ON links(message_id, url)");
        } catch (SQLException e) {
            log.error("DUMP {}: ошибка подготовки схемы: {}", chatId, e.getMessage(), e);
        }
    }

    // ===== запросы MAX(message_id) =====
    public long getLastSavedMessageId(long chatId)  { return maxOrZero(chatId, "messages");  }
    public long getLastSavedPhotoId(long chatId)    { return maxOrZero(chatId, "photos");    }
    public long getLastSavedVideoId(long chatId)    { return maxOrZero(chatId, "videos");    }
    public long getLastSavedAudioId(long chatId)    { return maxOrZero(chatId, "audio");     }
    public long getLastSavedDocumentId(long chatId) { return maxOrZero(chatId, "documents"); }
    public long getLastSavedLinkId(long chatId)     { return maxOrZero(chatId, "links");     }

    private long maxOrZero(long chatId, String table) {
        try (Connection c = openDump(chatId)) { return maxOf(c, table); }
        catch (SQLException e) { log.warn("Последний {}: {}", table, e.getMessage()); return 0L; }
    }

    // ===== очистка БД и файлов =====
    public void clearDumpDatabasesAndDeleteFiles() {
        try { Files.createDirectories(dbDir); } catch (Exception ignore) {}
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "DUMP_*.db")) {
            for (Path p : ds) {
                deleteDbWithSidecars(p);
                log.info("Удалён файл DUMP-БД: {}", p.getFileName());
            }
        } catch (IOException e) {
            log.error("Ошибка обхода каталога с БД: {}", e.getMessage(), e);
        }

        try { Files.createDirectories(filesDir); } catch (Exception ignore) {}
        if (!Files.isDirectory(filesDir)) {
            log.warn("Каталог tdlib/files не найден — очистка пропущена");
            return;
        }

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(filesDir)) {
            for (Path sub : ds) {
                if (Files.isDirectory(sub)) {
                    deleteDirectoryContents(sub);
                    log.info("Очищено содержимое {}", sub);
                } else {
                    try { Files.deleteIfExists(sub); }
                    catch (Exception ex) { log.warn("Не удалось удалить файл {}: {}", sub, ex.getMessage()); }
                }
            }
        } catch (IOException e) {
            log.error("Ошибка очистки tdlib/files/*: {}", e.getMessage(), e);
        }
    }

    // ====== helpers ======
    private void ensureDumpMetadata(long chatId) {
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e) {
            log.error("DUMP {}: ошибка создания таблицы metadata: {}", chatId, e.getMessage(), e);
        }
    }

    private void dedupTable(Connection c, String table, String keyExpr) throws SQLException {
        String sql = "DELETE FROM " + q(table) + " WHERE rowid NOT IN (" +
                "SELECT MIN(rowid) FROM " + q(table) + " GROUP BY " + keyExpr + ")";
        try (Statement s = c.createStatement()) {
            int removed = s.executeUpdate(sql);
            if (removed > 0) log.info("Дедупликация {}: удалено {} дублей по ({})", table, removed, keyExpr);
        }
    }

    private long maxOf(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(message_id), 0) FROM " + q(table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private void deleteDbWithSidecars(Path dbFile) {
        try { Files.deleteIfExists(dbFile); }                                                catch (IOException ignore) {}
        try { Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-wal")); }  catch (IOException ignore) {}
        try { Files.deleteIfExists(dbFile.resolveSibling(dbFile.getFileName() + "-shm")); }  catch (IOException ignore) {}
    }

    private void deleteDirectoryContents(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (!dir.equals(d)) Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path dumpDbPath(long chatId) {
        // Используем только chatId для имени файла
        String fn = "DUMP_" + chatId + ".db";
        return dbDir.resolve(fn);
    }

    private Connection openDump(long chatId) throws SQLException {
        try { Files.createDirectories(dbDir); } catch (Exception ignore) {}
        return DriverManager.getConnection("jdbc:sqlite:" + dumpDbPath(chatId));
    }

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }
}