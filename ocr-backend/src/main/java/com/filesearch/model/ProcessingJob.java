package com.filesearch.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "processing_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 200)
    private String jobName;

    @Column(name = "status", length = 20)
    private String status;

    @Column(name = "total_files")
    private Integer totalFiles;

    @Column(name = "processed_files")
    private Integer processedFiles;

    @Column(name = "failed_files")
    private Integer failedFiles;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        startedAt = OffsetDateTime.now();
        if (status == null) {
            status = "STARTED";
        }
        if (totalFiles == null) totalFiles = 0;
        if (processedFiles == null) processedFiles = 0;
        if (failedFiles == null) failedFiles = 0;
    }
}