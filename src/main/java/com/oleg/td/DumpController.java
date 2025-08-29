package com.oleg.td;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Простой REST-контроллер:
 * - POST /start    — старт дампа
 * - POST /stop     — останов
 * - GET  /progress — прогресс
 */
@RestController
public class DumpController {
    private final DumpService dumpService;

    public DumpController(DumpService dumpService) {
        this.dumpService = dumpService;
    }

    @PostMapping("/start")
    public void start(@RequestBody DumpRequest request) {
        dumpService.startDump(request);
    }

    @PostMapping("/stop")
    public void stop() {
        dumpService.stopDump();
    }

    @GetMapping("/progress")
    public DumpProgress progress() {
        return dumpService.getProgress();
    }
}
