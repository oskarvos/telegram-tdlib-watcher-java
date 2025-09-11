package com.oleg.td.search.api;

import com.oleg.td.dump.core.ChatDumpCoordinator;
import com.oleg.td.integrations.telegram.ChatResolver;
import com.oleg.td.search.persistence.SearchDbManager;
import com.oleg.td.search.core.SearchService;
import com.oleg.td.search.model.SearchResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService searchService;
    private final ChatDumpCoordinator chatDumpCoordinator;
    private final ChatResolver chatResolver;
    private final SearchDbManager databaseManager; // <--- добавить

    public SearchController(SearchService searchService,
                            ChatDumpCoordinator chatDumpCoordinator,
                            ChatResolver chatResolver,
                            SearchDbManager databaseManager) {
        this.searchService = searchService;
        this.chatDumpCoordinator = chatDumpCoordinator;
        this.chatResolver = chatResolver;
        this.databaseManager = databaseManager;
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
        searchService.stopSearch();
        databaseManager.clearSearchChatDatabases(); // только SEARCH-базы
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
