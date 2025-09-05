package com.oleg.td;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final ChatMonitor chatMonitor;
    private final DatabaseManager db;

    public MonitorController(ChatMonitor chatMonitor, DatabaseManager db) {
        this.chatMonitor = chatMonitor;
        this.db = db;
    }

    @PostMapping("/start")
    public String startMonitoring(@RequestBody MonitorConfig config) {
        if (config.getMonitoredChats() == null || config.getMonitoredChats().isEmpty()) {
            return "Ошибка: не указаны чаты для мониторинга";
        }
        if (config.getKeywords() == null || config.getKeywords().isEmpty()) {
            return "Ошибка: не указаны ключевые слова";
        }

        chatMonitor.startMonitoring(config);
        return "Мониторинг запущен - обработка всех сообщений";
    }

    @GetMapping("/results")
    public List<MonitorResult> getResults(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo) {

        return db.getMonitorResults(keyword, dateFrom != null ? LocalDateTime.parse(dateFrom) : null,
                dateTo != null ? LocalDateTime.parse(dateTo) : null);
    }

    @PostMapping("/reset")
    public String resetMonitoring(@RequestParam(required = false) Long chatId) {
        if (chatId != null) {
            // Логика сброса состояния для конкретного чата
            return "Состояние мониторинга для чата " + chatId + " сброшено";
        } else {
            // Логика полного сброса
            return "Состояние мониторинга полностью сброшено";
        }
    }
}