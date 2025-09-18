package com.oleg.td.dump.persistence;

import java.sql.SQLException;

public interface DumpDbSession extends AutoCloseable {
    // транзакции
    void commit() throws SQLException;

    // metadata
    String loadMetadata(String key);

    void saveMetadata(String key, String value);

    // сущности
    void saveMessage(long messageId, long date, String senderId, Long replyTo, String text);

    void savePhoto(long messageId, Integer fileId, String remoteId,
                   Integer w, Integer h, String caption, String filePath);

    void saveVideo(long messageId, Integer fileId, String remoteId,
                   Integer duration, Integer w, Integer h, String caption, String filePath);

    void saveAudio(long messageId, Integer fileId, String remoteId,
                   Integer duration, String mime, String filePath);

    void saveDocument(long messageId, Integer fileId, String remoteId,
                      String fileName, String mimeType, String filePath);

    void saveLink(long messageId, String url, String context);

    @Override
    void close();
}
