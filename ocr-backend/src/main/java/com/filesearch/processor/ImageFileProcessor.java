package com.filesearch.processor;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.service.PaddleOcrService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImageFileProcessor implements FileProcessor {

    private final PaddleOcrService paddleOcrService;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg",
            ".jpeg",
            ".png",
            ".gif",
            ".bmp",
            ".tif",
            ".tiff",
            ".webp"
    );

    @Override
    public boolean supports(String extension) {

        if (extension == null) {
            return false;
        }

        return IMAGE_EXTENSIONS.contains(
                extension.toLowerCase(Locale.ROOT)
        );
    }

    @Override
    public FileProcessResult process(
            FileProcessRequest request) throws Exception {

        log.info(
                "[IMAGE] 이미지 파일 처리 시작: {}",
                request.getFileName()
        );

        String extractedContent =
                paddleOcrService.extract(
                        request.getFilePath()
                );

        if (extractedContent == null ||
                extractedContent.isBlank()) {

            log.warn(
                    "[IMAGE] OCR 결과가 없습니다: {}",
                    request.getFileName()
            );
        } else {

            log.info(
                    "[IMAGE] OCR 추출 완료: {}",
                    request.getFileName()
            );
        }

        return FileProcessResult.builder()
                .content(extractedContent)
                .extractionType("IMAGE")
                .extractionMethod("PADDLE_OCR")
                .embeddedObjects(null)  // 맞는지 확인
                .build();
    }
}