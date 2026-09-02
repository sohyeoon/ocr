package com.filesearch.processor;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.service.PaddleOcrService;
import com.filesearch.service.TikaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentFileProcessor implements FileProcessor {

    private final TikaService tikaService;
    private final PaddleOcrService paddleOcrService;

    private static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf",
            ".txt",
            ".rtf",
            ".html",
            ".htm",
            ".xml"
    );

    @Override
    public boolean supports(String extension) {

        if (extension == null) {
            return false;
        }

        return DOCUMENT_EXTENSIONS.contains(
                extension.toLowerCase(Locale.ROOT)
        );
    }

    @Override
    public FileProcessResult process(
            FileProcessRequest request) throws Exception {

        log.info(
                "[DOCUMENT] 문서 파일 처리 시작: {}",
                request.getFileName()
        );

        String extractedContent = null;

        /*
         * 1차: Apache Tika
         */
        try {

            log.info(
                    "[TIKA] 텍스트 추출 시도: {}",
                    request.getFileName()
            );

            extractedContent =
                    tikaService.extract(
                            request.getFilePath()
                    );

            if (extractedContent != null &&
                    !extractedContent.isBlank()) {

                log.info(
                        "[TIKA] 텍스트 추출 성공: {}",
                        request.getFileName()
                );

                return FileProcessResult.builder()
                        .content(extractedContent)
                        .extractionType("TEXT")
                        .extractionMethod("TIKA")
                        .embeddedObjects(List.of())
                        .build();
            }

            log.warn(
                    "[TIKA] 추출 결과가 비어있습니다: {}",
                    request.getFileName()
            );

        } catch (Exception e) {

            log.warn(
                    "[TIKA] 텍스트 추출 실패: {} - {}",
                    request.getFileName(),
                    e.getMessage()
            );
        }

        /*
         * 2차: PaddleOCR
         */
        if (paddleOcrService.isEnabled()) {

            log.info(
                    "[OCR FALLBACK] PaddleOCR 재시도: {}",
                    request.getFileName()
            );

            String ocrContent =
                    paddleOcrService.extract(
                            request.getFilePath()
                    );

            if (ocrContent != null &&
                    !ocrContent.isBlank()) {

                log.info(
                        "[OCR FALLBACK] OCR 추출 성공: {}",
                        request.getFileName()
                );

                return FileProcessResult.builder()
                        .content(ocrContent)
                        .extractionType("TEXT")
                        .extractionMethod("PADDLE_OCR")
                        .embeddedObjects(List.of())
                        .build();
            }

            log.warn(
                    "[OCR FALLBACK] OCR 결과도 없습니다: {}",
                    request.getFileName()
            );
        }

        /*
         * Tika / OCR 모두 실패
         */
        return FileProcessResult.builder()
                .content("")
                .extractionType("TEXT")
                .extractionMethod("NONE")
                .embeddedObjects(List.of())
                .build();
    }
}