package com.oleg.td.dump.api;

import com.oleg.td.dump.core.DumpService;
import com.oleg.td.dump.persistence.DumpDbManager;
import org.springframework.web.bind.annotation.*;

/**
 * Контроллер для управления процессом дампа данных из Telegram
 * Предоставляет REST API для запуска, остановки, получения прогресса и очистки БД
 */
@RestController
@RequestMapping("/api/dump")
public class DumpController {

    private final DumpService dumpService;
    private final DumpDbManager dbManager;

    /**
     * Конструктор контроллера
     * @param dumpService сервис запуска/остановки дампа
     * @param dbManager менеджер работы с БД дампа
     */
    public DumpController(DumpService dumpService, DumpDbManager dbManager) {
        this.dumpService = dumpService;
        this.dbManager = dbManager;
    }

    /**
     * Запуск процесса дампа по указанным параметрам
     * POST /api/dump/start
     */
    @PostMapping("/start")
    public void start(@RequestBody DumpRequest request) {
        dumpService.startDump(request);
    }

    /**
     * Остановка текущего процесса дампа
     * POST /api/dump/stop
     */
    @PostMapping("/stop")
    public void stop() {
        dumpService.stopDump();
    }

    /**
     * Получение текущего прогресса дампа
     * GET /api/dump/progress
     */
    @GetMapping("/progress")
    public DumpProgress progress() {
        return dumpService.getProgress();
    }

    /**
     * Очистка всех баз данных дампа и скачанных файлов
     * DELETE /api/dump/database
     */
    @DeleteMapping("/database")
    public void deleteDatabaseAndFiles() {
        dumpService.stopDump();
        dbManager.clearDumpDatabasesAndDeleteFiles();
    }
}
