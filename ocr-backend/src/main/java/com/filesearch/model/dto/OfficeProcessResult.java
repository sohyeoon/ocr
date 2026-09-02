package com.filesearch.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfficeProcessResult {
    
    private String content;

    private String extractionType;

    private boolean hasEmbeddedObject;

    private List<EmbeddedObjectInfo> embeddedObjects;
}