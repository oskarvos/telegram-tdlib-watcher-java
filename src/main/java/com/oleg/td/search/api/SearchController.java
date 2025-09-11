package com.oleg.td.search.api;

import com.oleg.td.dump.core.ChatDumpCoordinator;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.search.persistence.SearchDbManager;
import com.oleg.td.search.core.SearchService;
import com.oleg.td.search.model.SearchResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchService searchService;
    private final ChatDumpCoordinator chatDumpCoordinator; // как было
    private final ChatResolver chatResolver;
    private final SearchDbManager databaseManager;

    public SearchController(SearchService searchService,
                            ChatDumpCoordinator chatDumpCoordinator,
                            ChatResolver chatResolver,
                            SearchDbManager databaseManager) {
        this.searchService = searchService;
        this.chatDumpCoordinator = chatDumpCoordinator;
        this.chatResolver = chatResolver;
        this.databaseManager = databaseManager;
    }

    // SearchController.java
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody SearchRequest req) {
        Map<String, Object> resp = new HashMap<>();
        try {
            if (req.getChats() == null || req.getChats().isEmpty()) {
                throw new IllegalArgumentException("Не переданы чаты для поиска");
            }
            // (опц.) провалидируем чаты сразу — чтобы не падать в фоне:
            for (String ref : req.getChats()) {
                chatResolver.resolveFlexible(ref.trim()); // если невалидно — кинет ошибку здесь
            }
            // не ограничиваем длину keyword — UI может слать regex
            searchService.startSearch(req);
            resp.put("started", true);
            resp.put("message", "Поиск запущен");
            return resp;
        } catch (Exception e) {
            log.error("Search start error: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }


    @PostMapping("/stop")
    public void stop() {
        searchService.stopSearch();
    }

    @GetMapping("/progress")
    public SearchProgress progress() {
        return searchService.getProgress();
    }

    @DeleteMapping("/database")
    public void deleteDatabase() {
        // 1) надёжно останавливаем поиск
        searchService.stopSearchAndWait(5_000);

        // 2) удаляем ВСЕ SEARCH БД-файлы целиком
        databaseManager.clearSearchChatDatabases();
    }

    // (опционально) эндпоинт для удаления одной БД конкретного чата:
    @DeleteMapping("/database/{chat}")
    public void deleteChatDatabase(@PathVariable("chat") String chatRef) {
        searchService.stopSearchAndWait(5_000);
        long chatId = chatResolver.resolveFlexible(chatRef);
        databaseManager.clearSearchDatabase(chatId);
    }

    @GetMapping("/results")
    public java.util.List<SearchResult> getResults(@RequestParam("chat") String chatRef,
                                                   @RequestParam(value = "limit", defaultValue = "1000") int limit,
                                                   @RequestParam(value = "offset", defaultValue = "0") int offset) {
        long chatId = chatResolver.resolveFlexible(chatRef);
        databaseManager.prepareSearchSchema(chatId); // ensure table exists
        return databaseManager.getSearchResults(chatId, limit, offset);
    }
}
