package com.oleg.td.monitor.api;

import com.oleg.td.monitor.core.MonitorService;
import com.oleg.td.monitor.persistence.MonitorDbManager;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {
    private final MonitorService service;
    private final MonitorDbManager db;

    public MonitorController(MonitorService service, MonitorDbManager db) {
        this.service = service;
        this.db = db;
    }

    @PostMapping("/start")
    public void start(@RequestBody MonitorRequest request) {
        service.startMonitoring(request);
    }

    @PostMapping("/stop")
    public void stop() {
        service.stopMonitoring();
    }

    @GetMapping("/progress")
    public MonitorProgress progress() {
        return service.getProgress();
    }

    @DeleteMapping("/database")
    public void deleteDatabase() {
        service.stopMonitoringAndWait(5_000);
        db.clearMonitorChatDatabases();
    }

    @GetMapping("/results")
    public java.util.List<com.oleg.td.monitor.model.MonitorHit> results(
            @RequestParam("chat") String chatRef,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {
        long chatId = service.resolveChatId(chatRef); // см. хелпер ниже
        return db.getMonitorResults(chatId, limit, offset);
    }
}
