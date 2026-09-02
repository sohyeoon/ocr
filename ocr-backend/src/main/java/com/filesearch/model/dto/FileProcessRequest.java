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
    
    // ---------------------------------------
    private Long parentId;
    private Integer depth;
    /* (원본)
     * fileId = 1
       parentId = null
       depth = 0
       (Embedded)
       fileId = 2
	   parentId = 1
	   depth = 1
    */
    // ---------------------------------------
}