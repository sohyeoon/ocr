package com.filesearch.service;

import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.model.ExtractedText;
import com.filesearch.model.FileMetadata;
import com.filesearch.repository.ExtractedTextRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExtractedTextService {

    private final ExtractedTextRepository extractedTextRepository;

    /**
     * 파일 처리 결과를 extracted_text 테이블에 저장
     *
     * FileProcessResult
     *        ↓
     * ExtractedText
     *        ↓
     * extracted_text
     */
    @Transactional
    public void save(
            FileMetadata metadata,
            FileProcessResult result) {

        if (metadata == null) {
            throw new IllegalArgumentException(
                    "파일 메타데이터가 없습니다."
            );
        }

        if (result == null) {
            throw new IllegalArgumentException(
                    "파일 처리 결과가 없습니다."
            );
        }

        if (result.getContent() == null ||
                result.getContent().isBlank()) {

            log.info(
                    "[EXTRACTED TEXT] 저장할 내용이 없습니다. fileId={}",
                    metadata.getId()
            );

            return;
        }

        ExtractedText extractedText =
                ExtractedText.builder()
                        .fileMetadata(metadata)
                        .content(result.getContent())
                        .extractionType(
                                result.getExtractionType()
                        )
                        .build();

        extractedTextRepository.save(extractedText);

        log.info(
                "[EXTRACTED TEXT] 저장 완료 - fileId={}, extractionType={}",
                metadata.getId(),
                result.getExtractionType()
        );
    }
}