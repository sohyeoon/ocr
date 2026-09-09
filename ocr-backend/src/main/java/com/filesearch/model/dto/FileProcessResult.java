package com.filesearch.model.dto;

import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;


@Data
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FileProcessResult {

    private String content;

    private String extractionType;

    private String extractionMethod;
    
    private boolean hasEmbeddedObject;

    private List<EmbeddedObjectInfo> embeddedObjects;

    private List<PageResult> pages;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PageResult {
        private Integer pageNumber;
        private String content;
        private String extractionMethod;
    }

    public boolean isHasEmbeddedObject() {
        return embeddedObjects != null && !embeddedObjects.isEmpty();
    }
}