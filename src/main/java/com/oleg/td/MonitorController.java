package com.oleg.td;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/monitor")
public class MonitorController {
    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @PostMapping("/start")
    public void startMonitoring(@RequestBody MonitorRequest request) {
        monitorService.startMonitoring(request);
    }

    @PostMapping("/stop")
    public void stopMonitoring() {
        monitorService.stopMonitoring();
    }

    @GetMapping("/status")
    public MonitorStatus getStatus() {
        return monitorService.getStatus();
    }
}