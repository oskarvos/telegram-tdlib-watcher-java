package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class MediaDownloader {
    private final TdJsonClient client;

    public MediaDownloader(TdJsonClient client) {
        this.client = client;
    }

    /** Качает файл и ждёт завершения: возвращает локальный путь или null по таймауту. */
    public String downloadFileGetPath(long fileId) {
        try {
            // 1) Стартуем загрузку (асинхронно)
            ObjectNode start = Utils.obj("downloadFile");
            start.put("file_id", fileId);
            start.put("priority", 1);
            start.put("offset", 0);
            start.put("limit", 0);       // 0 = до конца
            start.put("synchronous", false);
            client.request(start).get(); // ответ сразу "file", но не факт что скачан

            // 2) Опрос состояния файла до завершения или таймаута
            long deadline = System.currentTimeMillis() + 60_000; // 60 сек на файл
            String localPath = null;

            while (System.currentTimeMillis() < deadline) {
                ObjectNode get = Utils.obj("getFile");
                get.put("file_id", fileId);
                JsonNode f = client.request(get).get();
                if (!"file".equals(f.path("@type").asText())) break;

                JsonNode local = f.path("local");
                boolean done = local.path("is_downloading_completed").asBoolean(false);
                String path = local.path("path").asText(null);
                if (done && path != null && !path.isBlank()) {
                    localPath = path;
                    break;
                }
                Thread.sleep(200); // маленькая задержка между опросами
            }
            return localPath;
        } catch (Exception e) {
            return null;
        }
    }
}
