package com.filesearch.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessingStatus {
    private Long jobId;
    private String jobName;
    private String status;
    private int totalFiles;
    private int processedFiles;
    private int failedFiles;
    private double progressPercent;
    private String startedAt;
    private String completedAt;
    private String errorMessage;
}