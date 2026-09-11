package com.filesearch.service;

import java.io.File;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.model.dto.OfficeProcessResult;
import com.filesearch.model.ExtractedText;
import com.filesearch.model.FileMetadata;
import com.filesearch.model.FileStatus;
import com.filesearch.processor.FileProcessor;
import com.filesearch.repository.ExtractedTextRepository;
import com.filesearch.repository.FileMetadataRepository;
import com.filesearch.service.embedded.EmbeddedFileExtractService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileProcessingService {

    private final FileMetadataRepository fileMetadataRepository;
    private final ExtractedTextRepository extractedTextRepository;

    private final List<FileProcessor> fileProcessors;

    private final EmbeddedFileExtractService embeddedFileExtractService;

    private final OpenSearchService openSearchService;


    /**
     * 파일 처리 진입점
     */
    @Transactional
    public void process(FileProcessRequest request) {

        log.info(
                "[FILE PROCESS] 파일 처리 시작: {}",
                request.getFileName()
        );

        FileMetadata fileMetadata =
                fileMetadataRepository.findById(
                        request.getFileId()
                ).orElseThrow(() ->
                        new RuntimeException(
                                "파일 메타데이터를 찾을 수 없습니다: "
                                        + request.getFileId()
                        )
                );

        try {

            // ==========================================
            // 1. 상태 변경
            // ==========================================

            fileMetadata.setStatus(
                    FileStatus.PROCESSING
            );

            fileMetadataRepository.save(
                    fileMetadata
            );


            // ==========================================
            // 2. 파일 Processor 선택
            // ==========================================

            FileProcessor processor =
                    findProcessor(
                            request.getFileExtension()
                    );

            log.info(
                    "[FILE PROCESS] Processor 선택: {}",
                    processor.getClass().getSimpleName()
            );


            // ==========================================
            // 3. 파일 처리
            // ==========================================

            FileProcessResult result =
                    processor.process(request);


            // ==========================================
            // 4. 추출 결과 확인
            // ==========================================

            log.info(
                    "[FILE PROCESS] 추출 완료 - type={}, method={}, embedded={}",
                    result.getExtractionType(),
                    result.getExtractionMethod(),
                    result.isHasEmbeddedObject()
            );


            // ==========================================
            // 5. 텍스트 저장
            // ==========================================

            saveExtractedText(
                    fileMetadata,
                    result
            );


            // ==========================================
            // 6. Embedded Object 처리
            // ==========================================

            if (result.isHasEmbeddedObject()) {

                processEmbeddedObjects(
                        fileMetadata,
                        result.getEmbeddedObjects()
                );
            }


            // ==========================================
            // 7. OpenSearch 저장
            // ==========================================

            if (result.getContent() != null &&
                    !result.getContent().isBlank()) {

                openSearchService.indexDocument(
                        fileMetadata,
                        result.getContent()
                );
            }


            // ==========================================
            // 8. 완료
            // ==========================================

            fileMetadata.setStatus(
                    FileStatus.COMPLETED
            );

            fileMetadata.setProcessedAt(
                    OffsetDateTime.now()
            );

            fileMetadataRepository.save(
                    fileMetadata
            );

            log.info(
                    "[FILE PROCESS] 파일 처리 완료: {}",
                    request.getFileName()
            );

        } catch (Exception e) {

            log.error(
                    "[FILE PROCESS] 파일 처리 실패: {}",
                    request.getFileName(),
                    e
            );

            fileMetadata.setStatus(
                    FileStatus.FAILED
            );

            fileMetadata.setErrorMessage(
                    e.getMessage()
            );

            fileMetadata.setProcessedAt(
                    OffsetDateTime.now()
            );

            fileMetadataRepository.save(
                    fileMetadata
            );
        }
    }


    /**
     * 확장자에 맞는 Processor 검색
     */
    private FileProcessor findProcessor(
            String extension) {
        
        if (extension == null) {
            throw new IllegalArgumentException("확장자 정보가 없습니다.");
        }

        System.out.println("Searching processor for extension: " + extension);
        System.out.println("fileProcessors = " + fileProcessors);

        String withDot = extension.startsWith(".") ? extension : "." + extension;
        String withoutDot = extension.startsWith(".") ? extension.substring(1) : extension;

        return fileProcessors.stream()
                .filter(processor ->
                        processor.supports(extension) || 
                        processor.supports(withDot) || 
                        processor.supports(withoutDot)
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "지원하지 않는 파일 형식입니다: "
                                        + extension
                        )
                );
    }


    /**
     * 추출된 텍스트 DB 저장
     */
    private void saveExtractedText(
            FileMetadata fileMetadata,
            FileProcessResult result) {

        if (result.getPages() != null && !result.getPages().isEmpty()) {
            log.info("[FILE PROCESS] 페이지별 텍스트 저장 시작: {}", fileMetadata.getFileName());
            
            for (int i = 0; i < result.getPages().size(); i++) {
                FileProcessResult.PageResult page = result.getPages().get(i);
                
                ExtractedText extractedText =
                        ExtractedText.builder()
                                .fileMetadata(fileMetadata)
                                .content(page.getContent())
                                .pageNumber(page.getPageNumber())
                                .contentOrder(i + 1)
                                .extractionType(page.getExtractionMethod())
                                .build();
                
                extractedTextRepository.save(extractedText);
            }
            
            log.info("[FILE PROCESS] 총 {}개 페이지 저장 완료: {}", result.getPages().size(), fileMetadata.getFileName());
            return;
        }

        String content =
                result.getContent();

        if (content == null ||
                content.isBlank()) {

            log.info(
                    "[FILE PROCESS] 추출된 텍스트 없음: {}",
                    fileMetadata.getFileName()
            );

            return;
        }

        ExtractedText extractedText =
                ExtractedText.builder()
                        .fileMetadata(fileMetadata)
                        .content(content)
                        .extractionType(
                                result.getExtractionType()
                        )
                        .build();

        extractedTextRepository.save(
                extractedText
        );

        log.info(
                "[FILE PROCESS] 추출 텍스트 저장 완료: {}",
                fileMetadata.getFileName()
        );
    }


    /**
     * Embedded Object 처리
     *
     * 현재는 depth 1까지만 처리
     */
    private void processEmbeddedObjects(
            FileMetadata parentMetadata,
            List<EmbeddedObjectInfo> embeddedObjects)
            throws Exception {

        if (embeddedObjects == null ||
                embeddedObjects.isEmpty()) {

            return;
        }

        log.info(
                "[EMBEDDED] {}개 객체 처리 시작: {}",
                embeddedObjects.size(),
                parentMetadata.getFileName()
        );

        for (EmbeddedObjectInfo embeddedObject :
                embeddedObjects) {

            processEmbeddedFile(
                    parentMetadata,
                    embeddedObject
            );
        }

    }

    /**
     * Embedded 파일 하나 처리
     *
     * depth 1까지만 처리한다.
     *
     * 부모 파일
     *   ↓
     * Embedded 파일
     *
     * Embedded 파일 내부의 Embedded Object는 처리하지 않는다.
     */
    private void processEmbeddedFile(
            FileMetadata parentMetadata,
            EmbeddedObjectInfo embeddedObject) {

        log.info(
                "[EMBEDDED] fileName={}, extractedPath={}, extension={}",
                embeddedObject.getFileName(),
                embeddedObject.getExtractedPath(),
                embeddedObject.getFileExtension()
        );

        // ==========================================
        // 1. 실제 추출 파일 확인
        // ==========================================

        if (embeddedObject.getExtractedPath() == null ||
                embeddedObject.getExtractedPath().isBlank()) {

            log.warn(
                    "[EMBEDDED] 추출된 파일 경로가 없습니다: {}",
                    embeddedObject.getFileName()
            );

            return;
        }

        File embeddedFile =
                new File(
                        embeddedObject.getExtractedPath()
                );

        if (!embeddedFile.exists()) {

            log.warn(
                    "[EMBEDDED] 추출된 파일이 존재하지 않습니다: {}",
                    embeddedFile.getAbsolutePath()
            );

            return;
        }


        // ==========================================
        // 2. 이미 등록된 파일인지 확인
        // ==========================================

        if (fileMetadataRepository.existsByFilePath(
                embeddedFile.getAbsolutePath())) {

            log.info(
                    "[EMBEDDED] 이미 등록된 파일: {}",
                    embeddedFile.getName()
            );

            return;
        }


        // ==========================================
        // 3. Embedded Metadata 생성
        // ==========================================
        
        String extension =
                embeddedObject.getFileExtension();

        log.info(
                "[DEBUG-METADATA] 추출 파일명: {}, 판별된 확장자: [{}], EmbeddedObjectInfo 제공 확장자: [{}]",
                embeddedFile.getName(),
                extension,
                embeddedObject.getFileExtension()
        );

        String dbExtension = (extension != null && !extension.startsWith(".")) 
                             ? "." + extension 
                             : extension;

        FileMetadata embeddedMetadata =
                FileMetadata.builder()
                        .fileName(
                                embeddedFile.getName()
                        )
                        .filePath(
                                embeddedFile.getAbsolutePath()
                        )
                        .fileExtension(
                                dbExtension
                        )
                        .fileSize(
                                embeddedFile.length()
                        )
                        .status(
                                FileStatus.PENDING
                        )
                        .parentId(
                                parentMetadata.getId()
                        )
                        .depth(1)
                        .parentPageNumber(embeddedObject.getParentPageNumber())
                        .fileHash(embeddedObject.getFileHash())
                        .build();

        embeddedMetadata =
                fileMetadataRepository.save(
                        embeddedMetadata
                );

        log.info(
                "[EMBEDDED] Metadata 저장 완료 - " +
                "id={}, parentId={}, depth={}, file={}",
                embeddedMetadata.getId(),
                parentMetadata.getId(),
                embeddedMetadata.getDepth(),
                embeddedMetadata.getFileName()
        );


        // ==========================================
        // 4. Embedded 파일 다시 처리
        // ==========================================

        processEmbeddedFileContent(
                embeddedMetadata
        );
    }
    /**
     * Embedded 파일의 텍스트 추출
     *
     * 주의:
     * 여기서는 Embedded Object를 다시 처리하지 않는다.
     * depth 1까지만 지원하기 때문이다.
     */
    private void processEmbeddedFileContent(
            FileMetadata embeddedMetadata) {

        log.info(
                "[EMBEDDED PROCESS] 처리 시작: {}",
                embeddedMetadata.getFileName()
        );

        try {

            // ==========================================
            // 1. PROCESSING 상태
            // ==========================================

            embeddedMetadata.setStatus(
                    FileStatus.PROCESSING
            );

            fileMetadataRepository.save(
                    embeddedMetadata
            );


            // ==========================================
            // 2. FileProcessRequest 생성
            // ==========================================

            FileProcessRequest request =
                    FileProcessRequest.builder()
                            .fileId(
                                    embeddedMetadata.getId()
                            )
                            .fileName(
                                    embeddedMetadata.getFileName()
                            )
                            .filePath(
                                    embeddedMetadata.getFilePath()
                            )
                            .fileExtension(
                                    embeddedMetadata.getFileExtension()
                            )
                            .build();


            // ==========================================
            // 3. 확장자에 맞는 Processor 선택
            // ==========================================

            FileProcessor processor =
                    findProcessor(
                            embeddedMetadata.getFileExtension()
                    );

            log.info(
                    "[EMBEDDED PROCESS] Processor 선택: {}",
                    processor.getClass().getSimpleName()
            );


            // ==========================================
            // 4. 실제 텍스트 추출
            // ==========================================

            FileProcessResult result =
                    processor.process(request);

            log.info(
                    "[EMBEDDED PROCESS] 추출 완료 - " +
                    "file={}, type={}, method={}",
                    embeddedMetadata.getFileName(),
                    result.getExtractionType(),
                    result.getExtractionMethod()
            );


            // ==========================================
            // 5. ExtractedText 저장
            // ==========================================

            saveExtractedText(
                    embeddedMetadata,
                    result
            );


            // ==========================================
            // 6. OpenSearch 저장
            // ==========================================

            if (result.getContent() != null &&
                    !result.getContent().isBlank()) {

                openSearchService.indexDocument(
                        embeddedMetadata,
                        result.getContent()
                );
            }


            // ==========================================
            // 7. 완료
            // ==========================================

            embeddedMetadata.setStatus(
                    FileStatus.COMPLETED
            );

            embeddedMetadata.setProcessedAt(
                    OffsetDateTime.now()
            );

            fileMetadataRepository.save(
                    embeddedMetadata
            );

            log.info(
                    "[EMBEDDED PROCESS] 처리 완료: {}",
                    embeddedMetadata.getFileName()
            );

        } catch (Exception e) {

            log.error(
                    "[EMBEDDED PROCESS] 처리 실패: {}",
                    embeddedMetadata.getFileName(),
                    e
            );

            embeddedMetadata.setStatus(
                    FileStatus.FAILED
            );

            embeddedMetadata.setErrorMessage(
                    e.getMessage()
            );

            embeddedMetadata.setProcessedAt(
                    OffsetDateTime.now()
            );

            fileMetadataRepository.save(
                    embeddedMetadata
            );
        }
    }

    /**
     * Embedded 파일 하나 처리
     */
	/*
	 * private void processEmbeddedFile( FileMetadata parentMetadata,
	 * EmbeddedObjectInfo embeddedObject) {
	 * 
	 * log.info( "[EMBEDDED] fileName={}, extractedPath={}, extension={}",
	 * embeddedObject.getFileName(), embeddedObject.getExtractedPath(),
	 * embeddedObject.getFileExtension() );
	 * 
	 * File embeddedFile = new File( embeddedObject.getExtractedPath() );
	 * 
	 * log.info( "[EMBEDDED] Embedded 파일 등록: {}", embeddedFile.getAbsolutePath() );
	 * 
	 * // 실제 추출 파일이 존재하는지 확인 if (!embeddedFile.exists()) {
	 * 
	 * log.warn( "[EMBEDDED] 추출된 파일이 존재하지 않습니다: {}", embeddedFile.getAbsolutePath()
	 * );
	 * 
	 * return; }
	 * 
	 * // 이미 등록된 파일이면 건너뜀 if (fileMetadataRepository.existsByFilePath(
	 * embeddedFile.getAbsolutePath())) {
	 * 
	 * log.info( "[EMBEDDED] 이미 등록된 파일: {}", embeddedFile.getName() );
	 * 
	 * return; }
	 * 
	 * // ========================================== // Embedded 파일 Metadata 생성 //
	 * ==========================================
	 * 
	 * String extension = getFileExtension( embeddedFile.getName() );
	 * 
	 * FileMetadata embeddedMetadata = FileMetadata.builder() .fileName(
	 * embeddedFile.getName() ) .filePath( embeddedFile.getAbsolutePath() )
	 * .fileExtension( extension ) .fileSize( embeddedFile.length() ) .status(
	 * FileStatus.PENDING ) .parentId( parentMetadata.getId() ) .build();
	 * 
	 * fileMetadataRepository.save( embeddedMetadata );
	 * 
	 * log.info( "[EMBEDDED] Embedded Metadata 저장 완료 - id={}, parentId={}, file={}",
	 * embeddedMetadata.getId(), parentMetadata.getId(),
	 * embeddedMetadata.getFileName() ); }
	 */

    /**
     * 파일 확장자 추출
     */
    private String getFileExtension(
            String fileName) {

        if (fileName == null) {
            return "";
        }

        int index =
                fileName.lastIndexOf('.');

        if (index <= 0) {
            return "";
        }

        return fileName
                .substring(index)
                .toLowerCase();
    }
}