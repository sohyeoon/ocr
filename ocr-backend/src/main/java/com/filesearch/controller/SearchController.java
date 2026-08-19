package com.filesearch.controller;

import com.filesearch.model.dto.SearchRequest;
import com.filesearch.model.dto.SearchResult;
import com.filesearch.service.OpenSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final OpenSearchService openSearchService;

    /**
     * 통합 검색 API
     * 
     * GET /api/search?q={query}&page={page}&size={size}
     */
    @GetMapping
    public ResponseEntity<SearchResult> search(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SearchResult result = openSearchService.search(query.trim(), page, size);
        return ResponseEntity.ok(result);
    }

    /**
     * 통합 검색 API (POST 방식)
     */
    @PostMapping
    public ResponseEntity<SearchResult> searchPost(@RequestBody SearchRequest request) {
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        SearchResult result = openSearchService.search(
                request.getQuery().trim(), request.getPage(), request.getSize());
        return ResponseEntity.ok(result);
    }
}