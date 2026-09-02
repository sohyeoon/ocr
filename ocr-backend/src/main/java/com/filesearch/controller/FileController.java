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
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
// 추가
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;


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
    
    
    
    
    
    
    
    
    
    
    
    
    
    // ---- 개체 삽입 추가 ---------------------------------------------------
    /*private final EmbeddedFileExtractService embeddedFileExtractor;

    @PostMapping(
            value = "/extract",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_OCTET_STREAM_VALUE
    )
    public ResponseEntity<byte[]> extract(
            @RequestPart("file") MultipartFile multipartFile
    ) throws IOException {

        Path tempFile = Files.createTempFile(
                "source_",
                "_" + multipartFile.getOriginalFilename()
        );

        multipartFile.transferTo(tempFile);

        try {

            List<File> files =
                    embeddedFileExtractor.extract(tempFile.toFile());

            byte[] zipData = createZip(files);

            return ResponseEntity.ok()
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"embedded_files.zip\""
                    )
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(zipData);

        } finally {

            Files.deleteIfExists(tempFile);
        }
    }
    
    private byte[] createZip(List<File> files) throws IOException {

        ByteArrayOutputStream outputStream =
                new ByteArrayOutputStream();

        try (ZipOutputStream zipOutputStream =
                     new ZipOutputStream(outputStream)) {

            for (File file : files) {

                ZipEntry entry =
                        new ZipEntry(file.getName());

                zipOutputStream.putNextEntry(entry);

                Files.copy(
                        file.toPath(),
                        zipOutputStream
                );

                zipOutputStream.closeEntry();
            }
        }

        return outputStream.toByteArray();
    }
    
    @PostMapping(
            value = "/inspect",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public List<String> inspect(
            @RequestPart("file") MultipartFile multipartFile
    ) throws IOException {

        Path tempFile = Files.createTempFile(
                "inspect_",
                "_" + multipartFile.getOriginalFilename()
        );

        multipartFile.transferTo(tempFile);

        try {
            return embeddedFileExtractor.inspectOleFile(
                    tempFile.toFile()
            );

        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    
    @PostMapping(
            value = "/extract-native",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<Resource> extractNative(
            @RequestPart("file") MultipartFile multipartFile
    ) throws Exception {

        Path tempFile = Files.createTempFile(
                "ole_",
                "_" + multipartFile.getOriginalFilename()
        );

        multipartFile.transferTo(tempFile);

        try {

            File extractedFile =
                    embeddedFileExtractor.extractEmbeddedFile(
                            tempFile.toFile()
                    );

            Resource resource =
                    new FileSystemResource(extractedFile);

            ContentDisposition contentDisposition =
                    ContentDisposition.builder("attachment")
                            .filename(extractedFile.getName(), StandardCharsets.UTF_8)
                            .build();
            
            return ResponseEntity.ok()
            		 .header(
            	                HttpHeaders.CONTENT_DISPOSITION,
            	                contentDisposition.toString()
            	        )
            	        .contentType(MediaType.APPLICATION_OCTET_STREAM)
            	        .body(resource);

        } finally {

            // 주의!
            // 여기서 바로 삭제하면 extractedFile도 삭제될 수 있으므로
            // 일단 tempFile만 삭제
            Files.deleteIfExists(tempFile);
        }
    }*/
}