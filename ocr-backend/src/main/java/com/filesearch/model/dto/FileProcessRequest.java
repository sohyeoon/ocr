package com.filesearch.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileProcessRequest {
    private String filePath;
    private String fileName;
    private String fileExtension;
    private Long fileSize;
    private Long fileId;
}