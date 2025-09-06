package com.oleg.td.search;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {
    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
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
        searchService.deleteSearchDatabase();
    }

    @GetMapping("/results")
    public java.util.List<SearchResult> getResults() {
        // Реализацию этого метода нужно добавить в DatabaseManager
        return java.util.Collections.emptyList();
    }
}