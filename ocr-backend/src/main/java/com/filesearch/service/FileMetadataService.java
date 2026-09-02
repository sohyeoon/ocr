package com.filesearch.service;

import java.io.File;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.filesearch.model.FileMetadata;
import com.filesearch.model.FileStatus;
import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.repository.FileMetadataRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class FileMetadataService {

    private final FileMetadataRepository fileMetadataRepository;


    /**
     * 파일 Metadata 저장
     */
    public FileMetadata save(FileMetadata fileMetadata) {

        log.debug(
                "[METADATA] 파일 Metadata 저장: fileName={}, filePath={}",
                fileMetadata.getFileName(),
                fileMetadata.getFilePath()
        );

        return fileMetadataRepository.save(fileMetadata);
    }


    /**
     * ID로 파일 Metadata 조회
     */
    @Transactional(readOnly = true)
    public FileMetadata findById(Long fileId) {

        return fileMetadataRepository.findById(fileId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "파일 Metadata를 찾을 수 없습니다. fileId="
                                        + fileId
                        )
                );
    }


    /**
     * 파일 경로로 이미 등록되어 있는지 확인
     */
    @Transactional(readOnly = true)
    public boolean existsByFilePath(String filePath) {

        return fileMetadataRepository.existsByFilePath(filePath);
    }


    /**
     * 파일 처리 상태 변경
     */
    public FileMetadata updateStatus(
            Long fileId,
            FileStatus status) {

        FileMetadata fileMetadata = findById(fileId);

        fileMetadata.setStatus(status);

        log.info(
                "[METADATA] 파일 상태 변경: fileId={}, status={}",
                fileId,
                status
        );

        return fileMetadataRepository.save(fileMetadata);
    }


    /**
     * 처리 시작 상태로 변경
     */
    public FileMetadata markProcessing(Long fileId) {

        return updateStatus(
                fileId,
                FileStatus.PROCESSING
        );
    }


    /**
     * 처리 완료 상태로 변경
     */
    public FileMetadata markCompleted(Long fileId) {

        return updateStatus(
                fileId,
                FileStatus.COMPLETED
        );
    }


    /**
     * 처리 실패 상태로 변경
     */
    public FileMetadata markFailed(Long fileId) {

        return updateStatus(
                fileId,
                FileStatus.FAILED
        );
    }


    /**
     * Embedded 파일 Metadata 생성 및 저장
     *
     * parentId를 이용해 부모 파일과 연결한다.
     */
    public FileMetadata createEmbeddedMetadata(
            FileMetadata parentMetadata,
            String fileName,
            String filePath,
            String fileExtension,
            Long fileSize) {

        // 이미 등록된 파일인지 확인
        if (existsByFilePath(filePath)) {

            log.info(
                    "[METADATA] 이미 등록된 Embedded 파일: {}",
                    filePath
            );

            return null;
        }

        FileMetadata embeddedMetadata =
                FileMetadata.builder()
                        .fileName(fileName)
                        .filePath(filePath)
                        .fileExtension(fileExtension)
                        .fileSize(fileSize)
                        .status(FileStatus.PENDING)
                        .parentId(
                                parentMetadata.getId()
                        )
                        .build();

        FileMetadata savedMetadata =
                fileMetadataRepository.save(
                        embeddedMetadata
                );

        log.info(
                "[METADATA] Embedded Metadata 저장 완료: " +
                "id={}, parentId={}, fileName={}",
                savedMetadata.getId(),
                parentMetadata.getId(),
                savedMetadata.getFileName()
        );

        return savedMetadata;
    }


    /**
     * 부모 파일의 Embedded 파일 목록 조회
     */
    @Transactional(readOnly = true)
    public List<FileMetadata> findByParentId(Long parentId) {

        return fileMetadataRepository.findByParentId(
                parentId
        );
    }
    
    
    public FileMetadata createEmbeddedFile(
            FileMetadata parentFile,
            EmbeddedObjectInfo embeddedObject,
            int childDepth) {

        File file =
                new File(
                        embeddedObject.getExtractedPath()
                );

        /*
         * 이미 등록된 파일인지 확인
         */
        if (fileMetadataRepository.existsByFilePath(
                file.getAbsolutePath())) {

            log.info(
                    "[METADATA] 이미 등록된 Embedded 파일: {}",
                    file.getAbsolutePath()
            );

            return null;
        }

        /*
         * 파일 확장자
         */
        String extension =
                getFileExtension(
                        file.getName()
                );

        /*
         * 자식 파일 Metadata 생성
         */
        FileMetadata childFile =
                FileMetadata.builder()
                        .fileName(file.getName())
                        .filePath(file.getAbsolutePath())
                        .fileExtension(extension)
                        .fileSize(file.length())
                        .status(FileStatus.PENDING)

                        // 부모 파일 ID
                        .parentId(
                                        parentFile.getId()

                        )

                        // Embedded depth
                        .depth(childDepth)

                        .build();

        /*
         * DB 저장
         */
        FileMetadata savedFile =
                fileMetadataRepository.save(childFile);

        log.info(
                "[METADATA] Embedded 파일 Metadata 저장 완료. " +
                "id={}, parentId={}, depth={}, fileName={}",
                savedFile.getId(),
                parentFile.getId(),
                childDepth,
                savedFile.getFileName()
        );

        return savedFile;
    }
    
    private String getFileExtension(String fileName) {

        if (fileName == null || fileName.isBlank()) {
            return "";
        }

        int lastDotIndex = fileName.lastIndexOf('.');

        if (lastDotIndex == -1 || lastDotIndex == fileName.length() - 1) {
            return "";
        }

        return fileName
                .substring(lastDotIndex + 1)
                .toLowerCase();
    }
    
}