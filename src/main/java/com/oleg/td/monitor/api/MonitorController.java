package com.oleg.td.monitor.api;

import com.oleg.td.monitor.core.MonitorService;
import com.oleg.td.monitor.persistence.MonitorDbManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

/**
 * REST-контроллер мониторинга ключевых слов в чатах.
 * Запуск/остановка процесса, прогресс и доступ к результатам.
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private final MonitorService service;  // сервис мониторинга
    private final MonitorDbManager db;     // менеджер БД результатов

    public MonitorController(MonitorService service, MonitorDbManager db) {
        this.service = service;
        this.db = db;
    }

    // запуск процесса мониторинга
    @PostMapping("/start")
    public void start(@RequestBody MonitorRequest request) {
        if (request == null) throw new IllegalArgumentException("Тело запроса пусто");
        service.startMonitoring(request);
        log.info("Получен запрос на запуск мониторинга");
    }

    // остановка процесса мониторинга
    @PostMapping("/stop")
    public void stop() {
        service.stopMonitoring();
    }

    // текущий прогресс мониторинга
    @GetMapping("/progress")
    public MonitorProgress progress() {
        return service.getProgress();
    }

    // удаление всех БД мониторинга (с остановкой процесса)
    @DeleteMapping("/database")
    public void deleteDatabase() {
        service.stopMonitoringAndWait(5_000);
        db.clearMonitorChatDatabases();
        log.info("Все БД мониторинга удалены");
    }

    // получение результатов мониторинга по чату
    @GetMapping("/results")
    public java.util.List<com.oleg.td.monitor.model.MonitorHit> results(
            @RequestParam("chat") String chatRef,
            @RequestParam(value = "limit", required = false, defaultValue = "100") int limit,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset) {

        if (limit <= 0) limit = 1; // проверка границ
        if (offset < 0) offset = 0;

        long chatId = service.resolveChatId(chatRef); // резолв из строки
        return db.getMonitorResults(chatId, limit, offset);
    }
}
