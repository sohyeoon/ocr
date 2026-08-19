package com.filesearch.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchResult {
    private String query;
    private long totalHits;
    private int page;
    private int size;
    private long searchTimeMs;
    private List<SearchHit> hits;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SearchHit {
        private Long fileId;
        private String fileName;
        private String filePath;
        private String fileExtension;
        private String contentSnippet;
        private float score;
        private Integer pageNumber;
    }
}