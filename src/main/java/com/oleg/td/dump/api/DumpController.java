package com.oleg.td.dump.api;

import com.oleg.td.dump.core.DumpService;
import com.oleg.td.dump.persistence.DumpDbManager;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dump")
public class DumpController {
    private final DumpService dumpService;
    private final DumpDbManager db;

    public DumpController(DumpService dumpService, DumpDbManager db) {
        this.dumpService = dumpService;
        this.db = db;
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

    @DeleteMapping("/database")
    public void deleteDatabaseAndFiles() {
        dumpService.stopDump();
        db.clearDumpDatabasesAndDeleteFiles();
    }
}
