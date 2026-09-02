package com.filesearch.service;

import com.filesearch.kafka.FileProcessProducer;
import com.filesearch.model.FileMetadata;
import com.filesearch.model.FileStatus;
import com.filesearch.model.ProcessingJob;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.ProcessingStatus;
import com.filesearch.repository.FileMetadataRepository;
import com.filesearch.repository.ProcessingJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileScanService {

    private final FileMetadataRepository fileMetadataRepository;
    private final ProcessingJobRepository processingJobRepository;
    private final FileProcessProducer fileProcessProducer;

    @Value("${file-scan.base-path}")
    private String basePath;

    @Value("${file-scan.supported-extensions}")
    private List<String> supportedExtensions;

    /**
     * 지정된 경로의 파일을 스캔하고 Kafka로 처리 요청 전송
     */
    @Async
    public void startFileScan() {
        log.info("파일 스캔 시작: {}", basePath);

        // 작업 생성
        ProcessingJob job = ProcessingJob.builder()
                .jobName("File Scan - " + basePath)
                .status("RUNNING")
                .build();
        processingJobRepository.save(job);

        try {
            File baseDir = new File(basePath);
            if (!baseDir.exists() || !baseDir.isDirectory()) {
                throw new IOException("스캔 경로가 존재하지 않거나 디렉토리가 아닙니다: " + basePath);
            }

            List<File> files = scanFiles(baseDir);  // 지원되는 형식인지 확인 후 파일 목록 반환
            job.setTotalFiles(files.size());
            processingJobRepository.save(job);

            int processed = 0;
            int failed = 0;

            for (File file : files) {
                try {
                    processFile(file);
                    processed++;
                } catch (Exception e) {
                    log.error("파일 처리 요청 실패: {}", file.getName(), e);
                    failed++;
                }
                job.setProcessedFiles(processed);
                job.setFailedFiles(failed);
                processingJobRepository.save(job);
            }

            job.setStatus("COMPLETED");
            job.setCompletedAt(OffsetDateTime.now());

        } catch (Exception e) {
            log.error("파일 스캔 중 오류 발생", e);
            job.setStatus("FAILED");
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(OffsetDateTime.now());
        }

        processingJobRepository.save(job);
        log.info("파일 스캔 완료");
    }

    /**
     * 디렉토리를 재귀적으로 스캔하여 지원되는 파일 목록 반환
     */
    private List<File> scanFiles(File directory) throws IOException {
        return Files.walk(directory.toPath())
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    return supportedExtensions.stream()
                            .anyMatch(ext -> fileName.endsWith(ext.toLowerCase()));
                })
                .map(Path::toFile)
                .toList();
    }

    /**
     * 개별 파일을 메타데이터로 저장하고 Kafka에 처리 요청 전송
     */
    private void processFile(File file) {
        String filePath = file.getAbsolutePath();

        // 이미 처리된 파일인지 확인
        if (fileMetadataRepository.existsByFilePath(filePath)) {
            log.debug("이미 등록된 파일 건너뜀: {}", file.getName());
            return;
        }

        String extension = getFileExtension(file.getName());

        // 파일 메타데이터 저장
        FileMetadata metadata = FileMetadata.builder()
                .fileName(file.getName())
                .filePath(filePath)
                .fileExtension(extension)
                .fileSize(file.length())
                .status(FileStatus.PENDING)
                .build();
        fileMetadataRepository.save(metadata);

        // Kafka로 처리 요청 전송
        FileProcessRequest request = FileProcessRequest.builder()
                .fileId(metadata.getId())
                .fileName(file.getName())
                .filePath(filePath)
                .fileExtension(extension)
                .fileSize(file.length())
                .build();
        fileProcessProducer.sendFileProcessRequest(request);
    }

    /**
     * 현재 처리 상태 조회
     */
    public ProcessingStatus getCurrentStatus() {
        Optional<ProcessingJob> latestJob = processingJobRepository.findTopByOrderByStartedAtDesc();

        if (latestJob.isEmpty()) {
            return ProcessingStatus.builder()
                    .status("IDLE")
                    .totalFiles(0)
                    .processedFiles(0)
                    .failedFiles(0)
                    .progressPercent(0)
                    .build();
        }

        ProcessingJob job = latestJob.get();
        double progress = job.getTotalFiles() > 0
                ? (double) (job.getProcessedFiles() + job.getFailedFiles()) / job.getTotalFiles() * 100
                : 0;

        return ProcessingStatus.builder()
                .jobId(job.getId())
                .jobName(job.getJobName())
                .status(job.getStatus())
                .totalFiles(job.getTotalFiles())
                .processedFiles(job.getProcessedFiles())
                .failedFiles(job.getFailedFiles())
                .progressPercent(progress)
                .startedAt(job.getStartedAt() != null ? job.getStartedAt().toString() : null)
                .completedAt(job.getCompletedAt() != null ? job.getCompletedAt().toString() : null)
                .errorMessage(job.getErrorMessage())
                .build();
    }

    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot) : "";
    }
}