package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CompletableFuture;

public class MediaDownloader {
    private final TdJsonClient client;

    public MediaDownloader(TdJsonClient client) {
        this.client = client;
    }

    /** Загружает файл TDLib и возвращает локальный путь. Выполняется синхронно через request().get(). */
    public String downloadFileGetPath(long fileId) {
        try {
            ObjectNode req = Utils.obj("downloadFile");
            req.put("file_id", fileId);
            req.put("priority", 1);
            req.put("offset", 0);
            req.put("limit", 0); // 0 = до конца
            req.put("synchronous", true);

            JsonNode resp = client.request(req).get();
            if (!"file".equals(resp.path("@type").asText())) return null;
            String path = resp.path("local").path("path").asText(null);
            return (path != null && !path.isBlank()) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }
}
