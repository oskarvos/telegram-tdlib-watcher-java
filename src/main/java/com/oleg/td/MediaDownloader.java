package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MediaDownloader {
    private final TdJsonClient client;

    public MediaDownloader(TdJsonClient client) {
        this.client = client;
    }

    /** Если file.id == 0, пробуем резолвить по remote.id. fileType:
     *  fileTypePhoto|fileTypeVideo|fileTypeDocument|fileTypeAnimation|fileTypeAudio|fileTypeVoiceNote|fileTypeVideoNote|fileTypeSticker */
    public Long ensureFileId(JsonNode fileObj, String fileType) {
        long id = fileObj.path("id").asLong(0);
        if (id != 0) return id;

        String remoteId = fileObj.path("remote").path("id").asText(null);
        if (remoteId == null || remoteId.isBlank()) return null;

        try {
            ObjectNode req = Utils.obj("getRemoteFile");
            ObjectNode ft = req.putObject("file_type");
            ft.put("@type", fileType);
            req.put("remote_file_id", remoteId);

            JsonNode resp = client.request(req).get();
            if (!"file".equals(resp.path("@type").asText())) return null;
            long resolved = resp.path("id").asLong(0);
            return resolved == 0 ? null : resolved;
        } catch (Exception e) {
            return null;
        }
    }

    /** Качает файл и ждёт завершения: возвращает локальный путь или null по таймауту. */
    public String downloadFileGetPath(long fileId) {
        try {
            ObjectNode start = Utils.obj("downloadFile");
            start.put("file_id", fileId);
            start.put("priority", 1);
            start.put("offset", 0);
            start.put("limit", 0);
            start.put("synchronous", false);
            client.request(start).get();

            long deadline = System.currentTimeMillis() + 60_000;
            String localPath = null;
            while (System.currentTimeMillis() < deadline) {
                ObjectNode get = Utils.obj("getFile");
                get.put("file_id", fileId);
                JsonNode f = client.request(get).get();
                if (!"file".equals(f.path("@type").asText())) break;

                JsonNode local = f.path("local");
                boolean done = local.path("is_downloading_completed").asBoolean(false);
                String path = local.path("path").asText(null);
                if (done && path != null && !path.isBlank()) { localPath = path; break; }
                Thread.sleep(200);
            }
            return localPath;
        } catch (Exception e) {
            return null;
        }
    }
}
