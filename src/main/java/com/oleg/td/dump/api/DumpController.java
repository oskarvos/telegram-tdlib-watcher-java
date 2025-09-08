package com.oleg.td.dump.api;

import com.oleg.td.dump.core.DumpService;
import org.springframework.web.bind.annotation.*;


// was: @RestController (без префикса)
@RestController
@RequestMapping("/api/dump")
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

