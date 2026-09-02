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
public class EmbeddedObjectInfo {
    
	private String fileName;
	
	private String content;

    private String fileExtension;

    private String objectType;

    private String extractedPath;
}