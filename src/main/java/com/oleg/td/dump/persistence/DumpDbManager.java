package com.oleg.td.dump.persistence;

import com.oleg.td.integrations.telegram.ChatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class DumpDbManager {
    private static final Logger log = LoggerFactory.getLogger(DumpDbManager.class);

    private final Path dbDir = Paths.get("tdlib", "db");
    private final ChatResolver chatResolver;

    public DumpDbManager(ChatResolver chatResolver) {
        this.chatResolver = chatResolver;
        ensureBaseDir();
    }

    /* ===== helpers ===== */
    private static final Pattern INVALID = Pattern.compile("[\\\\/:*?\"<>|]");
    private static String q(String ident){ return "\"" + ident.replace("\"","\"\"") + "\""; }

    private void ensureBaseDir() {
        try {
            Files.createDirectories(dbDir);
            log.info("DUMP DB dir: {}", dbDir.toAbsolutePath());
        } catch (Exception e) {
            log.error("Cannot create dump DB dir {}: {}", dbDir.toAbsolutePath(), e.getMessage(), e);
        }
    }

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

    private Connection openDump(long chatId) throws SQLException {
        Path p = dumpDbPath(chatId);
        try { Files.createDirectories(p.getParent()); }
        catch (Exception e) { log.error("Cannot create parent dir for {}: {}", p.toAbsolutePath(), e.getMessage(), e); }
        String url = "jdbc:sqlite:" + p.toAbsolutePath();
        return DriverManager.getConnection(url);
    }

    /* ===== METADATA ===== */
    public void ensureDumpMetadata(long chatId){
        try (Connection c = openDump(chatId); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + q("metadata") + " (key TEXT PRIMARY KEY, value TEXT)");
        } catch (SQLException e){
            log.error("DUMP {}: cannot ensure metadata: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    public String loadMetadata(long chatId, String key){
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

    public void saveMetadata(long chatId, String key, String value){
        final String sql = "INSERT OR REPLACE INTO " + q("metadata") + " (key,value) VALUES(?,?)";
        try (Connection c = openDump(chatId);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("DUMP set meta '{}' err: {}", key, e.getMessage(), e); }
    }

    /* ===== SCHEMA ===== */
    public void prepareSchema(long chatId){
        try (Connection c = openDump(chatId); Statement s = c.createStatement()){
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
            log.info("DUMP schema ready at {}", dumpDbPath(chatId).toAbsolutePath());
        } catch (SQLException e){
            log.error("DUMP {}: schema error: {}", chatName(chatId), e.getMessage(), e);
        }
    }

    /* ===== LAST SAVED ===== */
    private long maxOf(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(message_id), 0) FROM " + q(table))) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
    public long getLastSavedMessageId(long chatId){
        try (Connection c = openDump(chatId)) { return maxOf(c, "messages"); }
        catch (SQLException e){ log.warn("last messages err: {}", e.getMessage()); return 0L; }
    }
    public long getLastSavedPhotoId(long chatId){
        try (Connection c = openDump(chatId)) { return maxOf(c, "photos"); }
        catch (SQLException e){ log.warn("last photos err: {}", e.getMessage()); return 0L; }
    }
    public long getLastSavedVideoId(long chatId){
        try (Connection c = openDump(chatId)) { return maxOf(c, "videos"); }
        catch (SQLException e){ log.warn("last videos err: {}", e.getMessage()); return 0L; }
    }
    public long getLastSavedAudioId(long chatId){
        try (Connection c = openDump(chatId)) { return maxOf(c, "audio"); }
        catch (SQLException e){ log.warn("last audio err: {}", e.getMessage()); return 0L; }
    }
    public long getLastSavedDocumentId(long chatId){
        try (Connection c = openDump(chatId)) { return maxOf(c, "documents"); }
        catch (SQLException e){ log.warn("last documents err: {}", e.getMessage()); return 0L; }
    }
    public long getLastSavedLinkId(long chatId){
        try (Connection c = openDump(chatId)) { return maxOf(c, "links"); }
        catch (SQLException e){ log.warn("last links err: {}", e.getMessage()); return 0L; }
    }

    /* ===== SAVE ===== */
    public void saveMessage(long chatId, long messageId, long date,
                            String senderId, Long replyTo, String text){
        final String sql = "INSERT INTO messages(message_id,date,sender_id,reply_to,text) VALUES(?,?,?,?,?)";
        try (Connection c = openDump(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            ps.setLong(2, date);
            ps.setString(3, senderId);
            if (replyTo == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, replyTo);
            ps.setString(5, text);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("saveMessage err: {}", e.getMessage(), e); }
    }

    public void savePhoto(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer w, Integer h, String caption, String filePath){
        final String sql = "INSERT INTO photos(message_id,file_id,remote_id,width,height,caption,file_path) VALUES(?,?,?,?,?,?,?)";
        try (Connection c = openDump(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            if (fileId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, fileId);
            ps.setString(3, remoteId);
            if (w == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, w);
            if (h == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, h);
            ps.setString(6, caption);
            ps.setString(7, filePath);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("savePhoto err: {}", e.getMessage(), e); }
    }

    public void saveVideo(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer duration, Integer w, Integer h, String caption, String filePath){
        final String sql = "INSERT INTO videos(message_id,file_id,remote_id,duration,width,height,caption,file_path) VALUES(?,?,?,?,?,?,?,?)";
        try (Connection c = openDump(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            if (fileId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, fileId);
            ps.setString(3, remoteId);
            if (duration == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, duration);
            if (w == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, w);
            if (h == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, h);
            ps.setString(7, caption);
            ps.setString(8, filePath);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("saveVideo err: {}", e.getMessage(), e); }
    }

    public void saveAudio(long chatId, long messageId, Integer fileId, String remoteId,
                          Integer duration, String mime, String filePath){
        final String sql = "INSERT INTO audio(message_id,file_id,remote_id,duration,mime,file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = openDump(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            if (fileId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, fileId);
            ps.setString(3, remoteId);
            if (duration == null) ps.setNull(4, Types.INTEGER); else ps.setInt(4, duration);
            ps.setString(5, mime);
            ps.setString(6, filePath);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("saveAudio err: {}", e.getMessage(), e); }
    }

    public void saveDocument(long chatId, long messageId, Integer fileId, String remoteId,
                             String fileName, String mimeType, String filePath){
        final String sql = "INSERT INTO documents(message_id,file_id,remote_id,file_name,mime_type,file_path) VALUES(?,?,?,?,?,?)";
        try (Connection c = openDump(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            if (fileId == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, fileId);
            ps.setString(3, remoteId);
            ps.setString(4, fileName);
            ps.setString(5, mimeType);
            ps.setString(6, filePath);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("saveDocument err: {}", e.getMessage(), e); }
    }

    public void saveLink(long chatId, long messageId, String url, String context){
        final String sql = "INSERT INTO links(message_id,url,context) VALUES(?,?,?)";
        try (Connection c = openDump(chatId); PreparedStatement ps = c.prepareStatement(sql)){
            ps.setLong(1, messageId);
            ps.setString(2, url);
            ps.setString(3, context);
            ps.executeUpdate();
        } catch (SQLException e){ log.error("saveLink err: {}", e.getMessage(), e); }
    }

    /* ==================== ОЧИСТКА DUMP-БАЗ И ФАЙЛОВ ==================== */

    // удалить все пользовательские таблицы в БД
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

    // очистить все DUMP *.db
    private void clearAllDumpDatabases() {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dbDir, "DUMP *.db")) {
            for (Path p : ds){
                try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + p.toAbsolutePath())) {
                    dropAllUserTables(c);
                    try (Statement s = c.createStatement()){ s.execute("VACUUM"); }
                    log.info("Cleared DUMP DB: {}", p.getFileName());
                } catch (SQLException e){
                    log.error("Clear DUMP {} err: {}", p.getFileName(), e.getMessage(), e);
                }
            }
        } catch (IOException e){
            log.error("List DUMP db err: {}", e.getMessage(), e);
        }
    }

    // удалить рекурсивно содержимое указанной папки
    private void deleteFolderRecursively(Path root) {
        if (root == null) return;
        if (!Files.exists(root)) {
            log.info("Files dir not found, skip: {}", root.toAbsolutePath());
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); }
                catch (IOException e){ log.warn("Delete {} err: {}", p, e.getMessage()); }
            });
            log.info("Deleted folder: {}", root.toAbsolutePath());
        } catch (IOException e) {
            log.error("Walk {} err: {}", root.toAbsolutePath(), e.getMessage(), e);
        }
    }

    // публичный метод для контроллера
    public void clearDumpDatabasesAndDeleteFiles() {
        clearAllDumpDatabases();
        // Удаляем обе возможные локации «files»
        deleteFolderRecursively(Paths.get("files"));
        deleteFolderRecursively(Paths.get("tdlib", "files"));
    }
}
