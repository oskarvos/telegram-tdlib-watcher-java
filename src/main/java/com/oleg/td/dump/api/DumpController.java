package com.oleg.td.dump.api;

import com.oleg.td.dump.core.DumpService;
import com.oleg.td.dump.persistence.DumpDbManager;
import org.springframework.web.bind.annotation.*;

/**
 * REST-контроллер для управления дампом Telegram.
 * Старт/стоп, прогресс и очистка БД/файлов.
 */
@RestController
@RequestMapping("/api/dump")
public class DumpController {

    private final DumpService dumpService;     // сервис дампа
    private final DumpDbManager dbManager;     // менеджер БД дампа

    public DumpController(DumpService dumpService, DumpDbManager dbManager) {
        this.dumpService = dumpService;
        this.dbManager = dbManager;
    }

    // запуск процесса дампа по указанным параметрам
    @PostMapping("/start")
    public void start(@RequestBody DumpRequest request) {
        dumpService.startDump(request);
    }

    // остановка текущего процесса дампа
    @PostMapping("/stop")
    public void stop() {
        dumpService.stopDump();
    }

    // получение текущего прогресса
    @GetMapping("/progress")
    public DumpProgress progress() {
        return dumpService.getProgress();
    }

    // очистка всех DUMP-БД и скачанных файлов
    @DeleteMapping("/database")
    public void deleteDatabaseAndFiles() {
        dumpService.stopDump();
        dbManager.clearDumpDatabasesAndDeleteFiles();
    }
}
