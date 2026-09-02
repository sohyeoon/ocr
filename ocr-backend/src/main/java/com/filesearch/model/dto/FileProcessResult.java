package com.filesearch.model.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FileProcessResult {

    private String content;

    private String extractionType;

    private String extractionMethod;

    private List<EmbeddedObjectInfo> embeddedObjects;

	public boolean isHasEmbeddedObject() {
		return embeddedObjects != null && !embeddedObjects.isEmpty();
	}
}