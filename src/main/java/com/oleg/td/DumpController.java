// ============================================================================
// File: src/main/java/com/oleg/td/DumpController.java
// Назначение: REST-контроллер для запуска/остановки дампа и запроса прогресса.
// ============================================================================
package com.oleg.td;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API: /start, /stop, /progress — для фронта.
 */
@RestController
public class DumpController {
    private final DumpService dumpService;

    /** @param dumpService Сервис управления дампом */
    public DumpController(DumpService dumpService) { this.dumpService = dumpService; }

    /** Запуск дампа. Возвращаем 202 сразу, сама работа идёт в том же процессе. */
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody DumpRequest request) {
        // Запуск синхронный; HTTP поток может висеть. Чтобы фронт не ждал — не ждём ответ, а сразу 202.
        // Реальная работа идёт в сервисе (single-thread TDLib, но веб-слой может обрабатывать /progress).
        new Thread(() -> dumpService.startDump(request), "dump-worker").start();
        return ResponseEntity.accepted().body("Запуск дампа инициирован");
    }

    /** Остановить текущий дамп. */
    @PostMapping("/stop")
    public ResponseEntity<?> stop() { dumpService.stopDump(); return ResponseEntity.ok("Остановлено"); }

    /** Прогресс в процентах и флаг активности. */
    @GetMapping("/progress")
    public DumpProgress progress() { return dumpService.getProgress(); }
}