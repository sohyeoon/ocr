package com.filesearch.service.embedded;

import com.filesearch.kafka.FileProcessProducer;
import com.filesearch.model.FileMetadata;
import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.service.FileMetadataService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddedObjectService {

    private final FileMetadataService fileMetadataService;
    private final FileProcessProducer fileProcessProducer;

    @Value("${file-processing.max-embedded-depth:1}")
    private int maxEmbeddedDepth;

    /**
     * Embedded Object 처리
     *
     * @param parentFile 부모 파일
     * @param embeddedObjects 추출된 Embedded Object 목록
     */
    public void processEmbeddedObjects(
            FileMetadata parentFile,
            List<EmbeddedObjectInfo> embeddedObjects) {

        if (embeddedObjects == null || embeddedObjects.isEmpty()) {
            log.debug(
                    "[EMBEDDED] Embedded Object 없음. fileId={}",
                    parentFile.getId()
            );
            return;
        }

        Integer parentDepth = parentFile.getDepth();

        if (parentDepth == null) {
            parentDepth = 0;
        }

        /*
         * 최대 depth 초과 여부
         */
        if (parentDepth >= maxEmbeddedDepth) {
            log.info(
                    "[EMBEDDED] 최대 depth 도달. fileId={}, depth={}, maxDepth={}",
                    parentFile.getId(),
                    parentDepth,
                    maxEmbeddedDepth
            );
            return;
        }

        int childDepth = parentDepth + 1;

        for (EmbeddedObjectInfo embeddedObject : embeddedObjects) {

            try {
                processEmbeddedObject(
                        parentFile,
                        embeddedObject,
                        childDepth
                );

            } catch (Exception e) {

                log.error(
                        "[EMBEDDED] Embedded Object 처리 실패. " +
                        "parentFileId={}, fileName={}",
                        parentFile.getId(),
                        embeddedObject.getFileName(),
                        e
                );
            }
        }
    }

    /**
     * 개별 Embedded Object 처리
     */
    private void processEmbeddedObject(
            FileMetadata parentFile,
            EmbeddedObjectInfo embeddedObject,
            int childDepth) {

        String extractedPath =
                embeddedObject.getExtractedPath();

        File extractedFile =
                new File(extractedPath);

        if (!extractedFile.exists()) {

            log.warn(
                    "[EMBEDDED] 추출 파일이 존재하지 않습니다. path={}",
                    extractedPath
            );

            return;
        }

        /*
         * 1. 자식 파일 metadata 생성
         */
        FileMetadata childFile =
                fileMetadataService.createEmbeddedFile(
                        parentFile,
                        embeddedObject,
                        childDepth
                );

        log.info(
                "[EMBEDDED] 자식 파일 등록 완료. " +
                "parentId={}, childId={}, fileName={}, depth={}",
                parentFile.getId(),
                childFile.getId(),
                childFile.getFileName(),
                childDepth
        );

        /*
         * 2. Kafka를 통해 일반 파일 처리 pipeline으로 전달
         */
        FileProcessRequest request =
                FileProcessRequest.builder()
                        .fileId(childFile.getId())
                        .fileName(childFile.getFileName())
                        .filePath(childFile.getFilePath())
                        .fileExtension(childFile.getFileExtension())
                        .fileSize(childFile.getFileSize())
                        .parentId(parentFile.getId())
                        .depth(childDepth)
                        .build();

        fileProcessProducer.sendFileProcessRequest(request);

        log.info(
                "[EMBEDDED] 자식 파일 Kafka 전송 완료. " +
                "fileId={}, parentId={}, depth={}",
                childFile.getId(),
                parentFile.getId(),
                childDepth
        );
    }
}