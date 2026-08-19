package com.filesearch.controller;

import com.filesearch.model.FileMetadata;
import com.filesearch.model.dto.ProcessingStatus;
import com.filesearch.repository.FileMetadataRepository;
import com.filesearch.service.FileScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileScanService fileScanService;
    private final FileMetadataRepository fileMetadataRepository;

    /**
     * 파일 스캔 시작 - 지정된 경로의 파일을 읽기 시작
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> startScan() {
        fileScanService.startFileScan();
        return ResponseEntity.ok(Map.of(
                "message", "파일 스캔이 시작되었습니다.",
                "status", "STARTED"
        ));
    }

    /**
     * 현재 처리 상태 조회
     */
    @GetMapping("/status")
    public ResponseEntity<ProcessingStatus> getProcessingStatus() {
        ProcessingStatus status = fileScanService.getCurrentStatus();
        return ResponseEntity.ok(status);
    }

    /**
     * 파일 목록 조회 (페이징)
     */
    @GetMapping
    public ResponseEntity<Page<FileMetadata>> getFiles(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FileMetadata> files = fileMetadataRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok(files);
    }

    /**
     * 파일 상세 조회
     */
    @GetMapping("/{id}")
    public ResponseEntity<FileMetadata> getFile(@PathVariable Long id) {
        return fileMetadataRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}