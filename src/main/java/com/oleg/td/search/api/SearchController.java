package com.oleg.td.search.api;

import com.oleg.td.dump.core.ChatDumpCoordinator;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.persistence.DatabaseManager; // <--- добавить
import com.oleg.td.search.model.SearchResult;
import com.oleg.td.search.core.SearchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService searchService;
    private final ChatDumpCoordinator chatDumpCoordinator;
    private final ChatResolver chatResolver;
    private final DatabaseManager databaseManager; // <--- добавить

    public SearchController(SearchService searchService,
                            ChatDumpCoordinator chatDumpCoordinator,
                            ChatResolver chatResolver,
                            DatabaseManager databaseManager) { // <--- добавить
        this.searchService = searchService;
        this.chatDumpCoordinator = chatDumpCoordinator;
        this.chatResolver = chatResolver;
        this.databaseManager = databaseManager; // <--- добавить
    }

    @PostMapping("/start")
    public void start(@RequestBody SearchRequest request) {
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
        databaseManager.clearAllSearchDatabases(); // только SEARCH-базы
    }

    @GetMapping("/results")
    public java.util.List<SearchResult> getResults() {
        return java.util.Collections.emptyList();
    }
}
