package com.filesearch.processor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.multipdf.Splitter;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.service.PaddleOcrService;
import com.filesearch.service.TikaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * pdf 처리
 */

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

        // PDF 파일인 경우 페이지별 분할 처리 로직 실행
        if (request.getFileName().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return processPdf(request);
        }

        // 그 외 일반 문서 파일 처리
        return processGeneralDocument(request);
    }

    /**
     * PDF 파일을 페이지별로 분할하여 텍스트를 추출합니다.
     * 각 페이지마다 Tika -> OCR 순으로 시도하며, 결과를 PageResult 리스트로 반환합니다.
     */
    private FileProcessResult processPdf(FileProcessRequest request) throws Exception {
        List<FileProcessResult.PageResult> pages = new ArrayList<>();
        StringBuilder fullContent = new StringBuilder();

        try (PDDocument document = Loader.loadPDF(new File(request.getFilePath()))) {
            int pageCount = document.getNumberOfPages();
            log.info("[PDF] 총 페이지 수: {} - {}", request.getFileName(), pageCount);

            Splitter splitter = new Splitter();
            
            for (int i = 1; i <= pageCount; i++) {
                try (PDDocument pageDoc = splitter.split(document).get(i - 1)) {
                    Path tempPagePath = Files.createTempFile("pdf_page_" + i + "_", ".pdf");
                    pageDoc.save(tempPagePath.toFile());

                    PageExtractionResult pageResult = extractTextWithFallback(tempPagePath.toString(), request.getFileName(), i);
                    
                    if (pageResult != null && !pageResult.content().isBlank()) {
                        pages.add(FileProcessResult.PageResult.builder()
                                .pageNumber(i)
                                .content(pageResult.content())
                                .extractionMethod(pageResult.method())
                                .build());
                        
                        fullContent.append("[Page ").append(i).append("] (").append(pageResult.method()).append(")\n").append(pageResult.content()).append("\n\n");
                    }
                    
                    Files.deleteIfExists(tempPagePath);
                }
            }
        }

        String resultText = fullContent.toString().trim();
        return FileProcessResult.builder()
                .content(resultText)
                .pages(pages)
                .extractionType("TEXT")
                .extractionMethod(resultText.isBlank() ? "NONE" : "HYBRID_PAGE_BY_PAGE")
                .embeddedObjects(List.of())
                .build();
    }

    private record PageExtractionResult(String content, String method) {}

    /**
     * 단일 파일(페이지)에 대해 Tika 추출 시도 후 실패 시 OCR을 수행하는 헬퍼 메소드입니다.
     */
    private PageExtractionResult extractTextWithFallback(String filePath, String fileName, int pageNum) {
        String content = null;
        try {
            content = tikaService.extract(filePath);
            if (content != null && content.replaceAll("\\s+", "").length() >= 10) {
                return new PageExtractionResult(content, "TIKA");
            }
        } catch (Exception e) {
            log.warn("[TIKA] Page {} 추출 실패: {} - {}", pageNum, fileName, e.getMessage());
        }

        if (paddleOcrService.isEnabled()) {
            try {
                content = paddleOcrService.extract(filePath);
                if (content != null && !content.isBlank()) {
                    return new PageExtractionResult(content, "PADDLE_OCR");
                }
            } catch (Exception e) {
                log.warn("[OCR] Page {} 추출 실패: {} - {}", pageNum, fileName, e.getMessage());
            }
        }
        return new PageExtractionResult("", "NONE");
    }

    /**
     * PDF가 아닌 일반 문서 파일에 대한 기존 추출 로직입니다.
     */
    private FileProcessResult processGeneralDocument(FileProcessRequest request) throws Exception {
        String extractedContent = null;

        try {
            log.info("[TIKA] 텍스트 추출 시도: {}", request.getFileName());
            extractedContent = tikaService.extract(request.getFilePath());

            if (extractedContent != null && !extractedContent.isBlank()) {
                return FileProcessResult.builder()
                        .content(extractedContent)
                        .extractionType("TEXT")
                        .extractionMethod("TIKA")
                        .embeddedObjects(List.of())
                        .build();
            }
        } catch (Exception e) {
            log.warn("[TIKA] 텍스트 추출 실패: {} - {}", request.getFileName(), e.getMessage());
        }

        if (paddleOcrService.isEnabled()) {
            log.info("[OCR FALLBACK] PaddleOCR 재시도: {}", request.getFileName());
            String ocrContent = paddleOcrService.extract(request.getFilePath());
            if (ocrContent != null && !ocrContent.isBlank()) {
                return FileProcessResult.builder()
                        .content(ocrContent)
                        .extractionType("TEXT")
                        .extractionMethod("PADDLE_OCR")
                        .embeddedObjects(List.of())
                        .build();
            }
        }

        return FileProcessResult.builder()
                .content("")
                .extractionType("TEXT")
                .extractionMethod("NONE")
                .embeddedObjects(List.of())
                .build();
    }
}