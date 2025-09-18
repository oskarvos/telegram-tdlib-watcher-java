package com.oleg.td.dump.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oleg.td.integrations.tdlibs.TdJsonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Синхронный загрузчик файлов через TDLib с простым кэшем.
 * Возвращает локальный путь либо null.
 */
@Component
public class MediaDownloader {
    private static final Logger log = LoggerFactory.getLogger(MediaDownloader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TdJsonClient client;                       // TDLib-клиент
    private final ConcurrentHashMap<Integer, String> cache = new ConcurrentHashMap<>(); // кэш путей

    public MediaDownloader(TdJsonClient client) {
        this.client = client;
    }

    public void clearCache() { cache.clear(); }

    // блокирующее скачивание файла по file_id; возвращает локальный путь
    public String downloadBlocking(int fileId) {
        if (fileId <= 0) return null;

        // 1) Проверяем кэш, но доверяем только если файл действительно существует
        String cached = cache.get(fileId);
        if (cached != null && !cached.isBlank()) {
            try {
                var p = java.nio.file.Paths.get(cached);
                if (java.nio.file.Files.exists(p)) {
                    return cached; // валидный кэш
                } else {
                    cache.remove(fileId); // инвалидируем «битый» кэш
                }
            } catch (Exception ignore) {
                cache.remove(fileId);
            }
        }

        try {
            // 2) Первая попытка: downloadFile(synchronous=true)
            var dl = MAPPER.createObjectNode();
            dl.put("@type", "downloadFile");
            dl.put("file_id", fileId);
            dl.put("priority", 32);
            dl.put("offset", 0);
            dl.put("limit", 0);
            dl.put("synchronous", true);

            var resp = client.requestWithFloodWaitSyncLimited(dl, 300, TdJsonClient.Channel.MAIN);
            if (!"file".equals(resp.path("@type").asText())) {
                log.warn("downloadFile: неожиданный ответ TDLib: {}", resp.path("@type").asText());
                return null;
            }

            var local = resp.path("local");
            String path = local.path("path").asText(null);
            boolean done = local.path("is_downloading_completed").asBoolean(false);

            if (done && path != null && !path.isBlank() && fileExists(path)) {
                cache.put(fileId, path);
                return path;
            }

            // 3) Уточняем через getFile (вдруг TDLib уже знает новый путь/статус)
            var gf = MAPPER.createObjectNode();
            gf.put("@type", "getFile");
            gf.put("file_id", fileId);

            var resp2 = client.requestWithFloodWaitSyncLimited(gf, 120, TdJsonClient.Channel.MAIN);
            var local2 = resp2.path("local");
            String path2 = local2.path("path").asText(null);
            boolean done2 = local2.path("is_downloading_completed").asBoolean(false);

            if (done2 && path2 != null && !path2.isBlank() && fileExists(path2)) {
                cache.put(fileId, path2);
                return path2;
            }

            // 4) Защитная повторная попытка принудительной докачки
            // (на случай рассинхронизации статуса TDLib после ручного удаления файлов)
            var dl2 = MAPPER.createObjectNode();
            dl2.put("@type", "downloadFile");
            dl2.put("file_id", fileId);
            dl2.put("priority", 32);
            dl2.put("offset", 0);
            dl2.put("limit", 0);
            dl2.put("synchronous", true);

            var resp3 = client.requestWithFloodWaitSyncLimited(dl2, 300, TdJsonClient.Channel.MAIN);
            if ("file".equals(resp3.path("@type").asText())) {
                String path3 = resp3.path("local").path("path").asText(null);
                boolean done3 = resp3.path("local").path("is_downloading_completed").asBoolean(false);
                if (done3 && path3 != null && !path3.isBlank() && fileExists(path3)) {
                    cache.put(fileId, path3);
                    return path3;
                }
            }

            log.warn("Не удалось получить локальный путь для file_id={} после повторных попыток", fileId);
        } catch (Exception e) {
            log.warn("Ошибка скачивания file_id={}: {}", fileId, e.toString());
        }
        return null;
    }

    private boolean fileExists(String path) {
        try {
            var p = java.nio.file.Paths.get(path);
            return java.nio.file.Files.exists(p);
        } catch (Exception e) {
            return false;
        }
    }
}
