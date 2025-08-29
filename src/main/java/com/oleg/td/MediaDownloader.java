package com.oleg.td;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Простая синхронная загрузка через TDLib:
 * - downloadFile(..., synchronous=true) -> TDLib блочно вернёт "file" с локальным путём
 * - кэш по file_id, чтобы не качать одно и то же
 */
@Component
public class MediaDownloader {
    private static final Logger log = LoggerFactory.getLogger(MediaDownloader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;
    private final ConcurrentHashMap<Integer, String> cache = new ConcurrentHashMap<>();

    public MediaDownloader(TdJsonClient client) {
        this.client = client;
    }

    /**
     * Скачивает файл и возвращает локальный путь (или null, если не удалось).
     * Блокирующая, но быстрая — без ожидания updateFile.
     */
    public String downloadBlocking(int fileId) {
        if (fileId <= 0) return null;

        // кэш
        String cached = cache.get(fileId);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        try {
            // 1) Синхронная загрузка
            ObjectNode dl = MAPPER.createObjectNode();
            dl.put("@type", "downloadFile");
            dl.put("file_id", fileId);
            dl.put("priority", 32);   // максимум
            dl.put("offset", 0);
            dl.put("limit", 0);       // 0 = целиком
            dl.put("synchronous", true);

            ObjectNode resp = client.requestWithFloodWaitSyncLimited(dl, 300, TdJsonClient.Channel.MAIN);
            if (!"file".equals(resp.path("@type").asText())) {
                log.warn("downloadFile: неожиданный ответ: {}", resp.path("@type").asText());
                return null;
            }

            JsonNode local = resp.path("local");
            String path = local.path("path").asText(null);
            boolean completed = local.path("is_downloading_completed").asBoolean(false);

            if (completed && path != null && !path.isBlank()) {
                cache.putIfAbsent(fileId, path);
                log.debug("file_id={} скачан: {}", fileId, path);
                return path;
            }

            // 2) На всякий случай уточним состояние через getFile
            ObjectNode gf = MAPPER.createObjectNode();
            gf.put("@type", "getFile");
            gf.put("file_id", fileId);
            ObjectNode resp2 = client.requestWithFloodWaitSyncLimited(gf, 120, TdJsonClient.Channel.MAIN);

            JsonNode local2 = resp2.path("local");
            String path2 = local2.path("path").asText(null);
            boolean completed2 = local2.path("is_downloading_completed").asBoolean(false);

            if (completed2 && path2 != null && !path2.isBlank()) {
                cache.putIfAbsent(fileId, path2);
                log.debug("file_id={} скачан (через getFile): {}", fileId, path2);
                return path2;
            }

            log.warn("Не удалось получить путь после downloadFile/getFile (file_id={})", fileId);
        } catch (Exception e) {
            log.warn("Ошибка скачивания file_id={}: {}", fileId, e.toString());
        }
        return null;
    }
}
