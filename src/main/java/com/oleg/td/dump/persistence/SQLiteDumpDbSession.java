package com.oleg.td.dump.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

final class SQLiteDumpDbSession implements DumpDbSession {

    private static final Logger log = LoggerFactory.getLogger(SQLiteDumpDbSession.class);
    private static final int BATCH_LIMIT = 1000;

    private final long chatId;
    private final Connection c;

    private final PreparedStatement insMsg;
    private final PreparedStatement insPhoto;
    private final PreparedStatement insVideo;
    private final PreparedStatement insAudio;
    private final PreparedStatement insDoc;
    private final PreparedStatement insLink;
    private final PreparedStatement selMeta;
    private final PreparedStatement upsertMeta;

    private int pendingOps = 0;

    SQLiteDumpDbSession(long chatId, Connection c) throws SQLException {
        this.chatId = chatId;
        this.c = c;

        insMsg   = c.prepareStatement("INSERT OR IGNORE INTO messages(message_id,date,sender_id,reply_to,text) VALUES(?,?,?,?,?)");
        insPhoto = c.prepareStatement("INSERT OR IGNORE INTO photos(message_id,file_id,remote_id,width,height,caption,file_path) VALUES(?,?,?,?,?,?,?)");
        insVideo = c.prepareStatement("INSERT OR IGNORE INTO videos(message_id,file_id,remote_id,duration,width,height,caption,file_path) VALUES(?,?,?,?,?,?,?,?)");
        insAudio = c.prepareStatement("INSERT OR IGNORE INTO audio(message_id,file_id,remote_id,duration,mime,file_path) VALUES(?,?,?,?,?,?)");
        insDoc   = c.prepareStatement("INSERT OR IGNORE INTO documents(message_id,file_id,remote_id,file_name,mime_type,file_path) VALUES(?,?,?,?,?,?)");
        insLink  = c.prepareStatement("INSERT OR IGNORE INTO links(message_id,url,context) VALUES(?,?,?)");

        selMeta    = c.prepareStatement("SELECT value FROM \"metadata\" WHERE key=?");
        upsertMeta = c.prepareStatement("INSERT OR REPLACE INTO \"metadata\" (key,value) VALUES(?,?)");
    }

    private void bumpAndMaybeCommit() throws SQLException {
        if (++pendingOps >= BATCH_LIMIT) {
            c.commit();
            pendingOps = 0;
        }
    }

    @Override
    public void commit() throws SQLException {
        c.commit();
        pendingOps = 0;
    }

    @Override
    public String loadMetadata(String key) {
        try {
            selMeta.clearParameters();
            selMeta.setString(1, key);
            try (ResultSet rs = selMeta.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            log.warn("Ошибка чтения метаданных '{}': {}", key, e.getMessage());
        }
        return null;
    }

    @Override
    public void saveMetadata(String key, String value) {
        try {
            upsertMeta.clearParameters();
            upsertMeta.setString(1, key);
            upsertMeta.setString(2, value);
            upsertMeta.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка записи метаданных '{}': {}", key, e.getMessage(), e);
        }
    }

    @Override
    public void saveMessage(long messageId, long date, String senderId, Long replyTo, String text) {
        try {
            insMsg.clearParameters();
            insMsg.setLong(1, messageId);
            insMsg.setLong(2, date);
            insMsg.setString(3, senderId);
            if (replyTo == null) insMsg.setNull(4, Types.BIGINT); else insMsg.setLong(4, replyTo);
            insMsg.setString(5, text);
            insMsg.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка сохранения сообщения: {}", e.getMessage(), e);
        }
    }

    @Override
    public void savePhoto(long messageId, Integer fileId, String remoteId,
                          Integer w, Integer h, String caption, String filePath) {
        try {
            insPhoto.clearParameters();
            insPhoto.setLong(1, messageId);
            if (fileId == null) insPhoto.setNull(2, Types.INTEGER); else insPhoto.setInt(2, fileId);
            insPhoto.setString(3, remoteId);
            if (w == null) insPhoto.setNull(4, Types.INTEGER); else insPhoto.setInt(4, w);
            if (h == null) insPhoto.setNull(5, Types.INTEGER); else insPhoto.setInt(5, h);
            insPhoto.setString(6, caption);
            insPhoto.setString(7, filePath);
            insPhoto.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка сохранения фото: {}", e.getMessage(), e);
        }
    }

    @Override
    public void saveVideo(long messageId, Integer fileId, String remoteId,
                          Integer duration, Integer w, Integer h, String caption, String filePath) {
        try {
            insVideo.clearParameters();
            insVideo.setLong(1, messageId);
            if (fileId == null) insVideo.setNull(2, Types.INTEGER); else insVideo.setInt(2, fileId);
            insVideo.setString(3, remoteId);
            if (duration == null) insVideo.setNull(4, Types.INTEGER); else insVideo.setInt(4, duration);
            if (w == null) insVideo.setNull(5, Types.INTEGER); else insVideo.setInt(5, w);
            if (h == null) insVideo.setNull(6, Types.INTEGER); else insVideo.setInt(6, h);
            insVideo.setString(7, caption);
            insVideo.setString(8, filePath);
            insVideo.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка сохранения видео: {}", e.getMessage(), e);
        }
    }

    @Override
    public void saveAudio(long messageId, Integer fileId, String remoteId,
                          Integer duration, String mime, String filePath) {
        try {
            insAudio.clearParameters();
            insAudio.setLong(1, messageId);
            if (fileId == null) insAudio.setNull(2, Types.INTEGER); else insAudio.setInt(2, fileId);
            insAudio.setString(3, remoteId);
            if (duration == null) insAudio.setNull(4, Types.INTEGER); else insAudio.setInt(4, duration);
            insAudio.setString(5, mime);
            insAudio.setString(6, filePath);
            insAudio.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка сохранения аудио: {}", e.getMessage(), e);
        }
    }

    @Override
    public void saveDocument(long messageId, Integer fileId, String remoteId,
                             String fileName, String mimeType, String filePath) {
        try {
            insDoc.clearParameters();
            insDoc.setLong(1, messageId);
            if (fileId == null) insDoc.setNull(2, Types.INTEGER); else insDoc.setInt(2, fileId);
            insDoc.setString(3, remoteId);
            insDoc.setString(4, fileName);
            insDoc.setString(5, mimeType);
            insDoc.setString(6, filePath);
            insDoc.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка сохранения документа: {}", e.getMessage(), e);
        }
    }

    @Override
    public void saveLink(long messageId, String url, String context) {
        try {
            insLink.clearParameters();
            insLink.setLong(1, messageId);
            insLink.setString(2, url);
            insLink.setString(3, context);
            insLink.executeUpdate();
            bumpAndMaybeCommit();
        } catch (SQLException e) {
            log.error("Ошибка сохранения ссылки: {}", e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try { commit(); }             catch (Exception ignore) {}
        try { insMsg.close(); }       catch (Exception ignore) {}
        try { insPhoto.close(); }     catch (Exception ignore) {}
        try { insVideo.close(); }     catch (Exception ignore) {}
        try { insAudio.close(); }     catch (Exception ignore) {}
        try { insDoc.close(); }       catch (Exception ignore) {}
        try { insLink.close(); }      catch (Exception ignore) {}
        try { selMeta.close(); }      catch (Exception ignore) {}
        try { upsertMeta.close(); }   catch (Exception ignore) {}
        try { c.close(); }            catch (Exception ignore) {}
    }
}
