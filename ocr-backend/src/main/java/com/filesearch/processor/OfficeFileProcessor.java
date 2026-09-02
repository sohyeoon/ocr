package com.filesearch.processor;

import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.processor.office.DocxProcessor;
import com.filesearch.processor.office.PptxProcessor;
import com.filesearch.processor.office.XlsxProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class OfficeFileProcessor implements FileProcessor {

    private static final Set<String> OFFICE_EXTENSIONS = Set.of(
            ".docx",
            ".pptx",
            ".xlsx"
    );

    private final DocxProcessor docxProcessor;
    private final PptxProcessor pptxProcessor;
    private final XlsxProcessor xlsxProcessor;

    /**
     * Office 파일 여부 확인
     */
    @Override
    public boolean supports(String extension) {

        if (extension == null) {
            return false;
        }

        return OFFICE_EXTENSIONS.contains(
                extension.toLowerCase(Locale.ROOT)
        );
    }

    /**
     * Office 파일 종류에 따라
     * 실제 Office Processor로 전달
     */
    @Override
    public FileProcessResult process(
            FileProcessRequest request) throws Exception {

        String extension = normalizeExtension(
                request.getFileExtension()
        );

        log.info(
                "[OFFICE] Office 파일 처리 시작 - file={}, extension={}",
                request.getFileName(),
                extension
        );

        return switch (extension) {

            case ".docx" -> {
                log.info(
                        "[OFFICE] DOCX Processor로 전달: {}",
                        request.getFileName()
                );

                yield docxProcessor.process(request);
            }

            case ".pptx" -> {
                log.info(
                        "[OFFICE] PPTX Processor로 전달: {}",
                        request.getFileName()
                );

                yield pptxProcessor.process(request);
            }

            case ".xlsx" -> {
                log.info(
                        "[OFFICE] XLSX Processor로 전달: {}",
                        request.getFileName()
                );

                yield xlsxProcessor.process(request);
            }

            default -> throw new IllegalArgumentException(
                    "지원하지 않는 Office 파일 형식: "
                            + request.getFileExtension()
            );
        };
    }

    /**
     * 확장자를 소문자 + 앞의 . 포함 형태로 통일
     */
    private String normalizeExtension(String extension) {

        if (extension == null) {
            return "";
        }

        String normalized =
                extension
                        .trim()
                        .toLowerCase(Locale.ROOT);

        if (!normalized.startsWith(".")) {
            normalized = "." + normalized;
        }

        return normalized;
    }
}