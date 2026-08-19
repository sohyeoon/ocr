package com.filesearch.controller;

import com.filesearch.model.FileMetadata;
import com.filesearch.repository.ExtractedTextRepository;
import com.filesearch.repository.FileMetadataRepository;
import com.filesearch.service.FileExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileDownloadController {

    private final FileMetadataRepository fileMetadataRepository;
    private final FileExtractionService fileExtractionService;

    /**
     * 파일 다운로드 API
     * 
     * GET /api/files/{id}/download
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        FileMetadata metadata = fileMetadataRepository.findById(id)
                .orElse(null);

        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(metadata.getFilePath());
        if (!file.exists()) {
            log.error("파일이 존재하지 않습니다: {}", metadata.getFilePath());
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);

        // 파일명 인코딩 (한글 파일명 지원)
        String encodedFileName = URLEncoder.encode(metadata.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        // MIME 타입 결정
        String contentType = getContentType(metadata.getFileExtension());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName)
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
                .body(resource);
    }

    /**
     * 파일 미리보기 (인라인 표시) API - 구현하다가 포기함 나중에 다시 구현 ㄱ
     * 
     * GET /api/files/{id}/preview
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> previewFile(@PathVariable Long id) {
        FileMetadata metadata = fileMetadataRepository.findById(id)
                .orElse(null);

        if (metadata == null) {
            return ResponseEntity.notFound().build();
        }

        File file = new File(metadata.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = getContentType(metadata.getFileExtension());
        String encodedFileName = URLEncoder.encode(metadata.getFileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName)
                .body(resource);
    }

    /**
     * 확장자에 따른 MIME 타입 반환
     */
    private String getContentType(String extension) {
        if (extension == null) return "application/octet-stream";

        return switch (extension.toLowerCase()) {
            case ".pdf" -> "application/pdf";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case ".txt" -> "text/plain; charset=UTF-8";
            default -> "application/octet-stream";
        };
    }

    /**
     * 파일 삭제 API
     *
     * GET /api/files/{id}/delete
     */
    /*@DeleteMapping("/{id}")
    public ResponseEntity<?> deleteFile(@PathVariable("id") Long fileId) {

        fileExtractionService.deleteFile(fileId);

        return ResponseEntity.ok(
                Map.of(
                        "success", true,
                        "message", "파일 삭제 완료"
                )
        );
    }*/
}