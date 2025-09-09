package com.oleg.td.search.api;

import com.oleg.td.dump.core.ChatDumpCoordinator;
import com.oleg.td.dump.persistence.DumpDbManager;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.search.core.SearchService;
import com.oleg.td.search.model.SearchResult;
import com.oleg.td.search.persistence.SearchDbManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService searchService;
    private final ChatDumpCoordinator chatDumpCoordinator;
    private final ChatResolver chatResolver;
    private final SearchDbManager searchDbManager; // Заменить DatabaseManager
    private final DumpDbManager dumpDbManager; // Добавить для полной очистки

    public SearchController(SearchService searchService,
                            ChatDumpCoordinator chatDumpCoordinator,
                            ChatResolver chatResolver,
                            SearchDbManager searchDbManager,
                            DumpDbManager dumpDbManager) {
        this.searchService = searchService;
        this.chatDumpCoordinator = chatDumpCoordinator;
        this.chatResolver = chatResolver;
        this.searchDbManager = searchDbManager;
        this.dumpDbManager = dumpDbManager;
    }

    @PostMapping("/start")
    public void start(@RequestBody SearchRequest request) {
        final String kw = request.getKeyword() == null ? "" : request.getKeyword();
        // Либо совсем убрать ограничение, либо сделать щедрее, например 256
        if (kw.length() > 256) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Ключевое слово слишком длинное"
            );
        }
        searchService.startSearch(request);
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
        searchDbManager.clearAllSearchDatabases(); // очистка всех SEARCH-БД
        dumpDbManager.resetAllSearchCheckpoints(); // сброс всех чекпоинтов
    }

    @GetMapping("/results")
    public java.util.List<SearchResult> getResults(@RequestParam("chat") String chatRef) {
        long chatId = chatResolver.resolveFlexible(chatRef);
        searchDbManager.prepareSearchSchema(chatId); // Заменить вызов
        return searchDbManager.getSearchResults(chatId); // Заменить вызов
    }
}
