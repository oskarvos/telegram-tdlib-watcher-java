package com.oleg.td;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Скачивает файлы TDLib по file_id и ждёт событие updateFile.
 * Логика:
 *  - отправляем метод downloadFile {file_id, priority, offset, limit, synchronous=false}
 *  - ждём updateFile, где придёт file.local.path и флаги завершения
 *  - возвращаем локальный путь к файлу
 *
 * Потокобезопасен: на каждый file_id создаётся свой CompletableFuture.
 */
@Component
public class MediaDownloader {
    private static final Logger log = LoggerFactory.getLogger(MediaDownloader.class);

    private final TdJsonClient td;
    private final UpdateRouter router;

    /** Ожидающие скачивания: file_id -> future(localPath) */
    private final Map<Integer, CompletableFuture<String>> waiters = new ConcurrentHashMap<>();

    public MediaDownloader(TdJsonClient td, UpdateRouter router) {
        this.td = td;
        this.router = router;

        // Подписываемся на updateFile один раз
        router.add(update -> {
            if (!"updateFile".equals(update.path("@type").asText())) return;

            try {
                var file = update.path("file");
                int fileId = file.path("id").asInt(-1);
                if (fileId < 0) return;

                var local = file.path("local");
                boolean completed = local.path("is_downloading_completed").asBoolean(false);
                boolean canBeDownloaded = file.path("can_be_downloaded").asBoolean(true);
                String path = local.path("path").asText(null);

                // Если либо загрузка завершена, либо файл уже локально доступен — резолвим
                if (completed || (path != null && !path.isBlank())) {
                    var fut = waiters.remove(fileId);
                    if (fut != null && !fut.isDone()) {
                        fut.complete(path);
                        log.info("Загрузка файла file_id={} завершена, путь: {}", fileId, path);
                    }
                } else if (!canBeDownloaded) {
                    // Файл недоступен для скачивания — фейлим ожидателя, если есть
                    var fut = waiters.remove(fileId);
                    if (fut != null && !fut.isDone()) {
                        fut.complete(null);
                        log.warn("Файл file_id={} недоступен для скачивания", fileId);
                    }
                }
            } catch (Exception e) {
                log.error("Ошибка обработки updateFile: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Скачивает файл (если ещё не скачан) и возвращает локальный путь.
     * @param fileId TDLib file.id (int)
     * @return локальный путь (или null, если скачать не удалось)
     */
    public String downloadBlocking(int fileId) {
        try {
            // Если уже есть ожидатель — переиспользуем
            var fut = waiters.computeIfAbsent(fileId, id -> {
                // Создаём новый ожидатель
                var f = new CompletableFuture<String>();
                // Отправляем команду скачивания
                ObjectNode req = Utils.obj("downloadFile");
                req.put("file_id", id);
                req.put("priority", 32);     // высокая, но не максимальная
                req.put("offset", 0);
                req.put("limit", 0);         // 0 = весь файл
                req.put("synchronous", false);
                td.send(req, TdJsonClient.Channel.MAIN);
                log.info("Запрошено скачивание файла file_id={}", id);
                return f;
            });

            // Ждём до 2 минут
            return fut.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            waiters.remove(fileId);
            log.warn("Таймаут скачивания file_id={}", fileId);
            return null;
        } catch (Exception e) {
            waiters.remove(fileId);
            log.error("Ошибка скачивания file_id={}: {}", fileId, e.getMessage(), e);
            return null;
        }
    }
}
