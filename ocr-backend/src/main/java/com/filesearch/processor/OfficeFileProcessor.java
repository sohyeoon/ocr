package com.filesearch.processor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.service.converter.LibreOfficeConverter;
import com.filesearch.service.embedded.EmbeddedFileExtractService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class OfficeFileProcessor implements FileProcessor {

    private static final Set<String> OFFICE_EXTENSIONS = Set.of(
            ".docx", ".doc", ".pptx", ".ppt", ".xlsx", ".xls"
    );

    private final LibreOfficeConverter libreOfficeConverter;
    private final DocumentFileProcessor documentFileProcessor;
    private final EmbeddedFileExtractService embeddedFileExtractService;

    @Override
    public boolean supports(String extension) {
        if (extension == null) return false;
        return OFFICE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }

    @Override
    public FileProcessResult process(FileProcessRequest request) throws Exception {
        String extension = normalizeExtension(request.getFileExtension());
        log.info("[OFFICE] Office 파일 처리 시작 - file={}, extension={}", request.getFileName(), extension);

        File inputFile = new File(request.getFilePath());
        File pdfFile = null;

        try {
            pdfFile = libreOfficeConverter.convertToPdf(inputFile);
            FileProcessRequest pdfRequest = FileProcessRequest.builder()
                    .fileId(request.getFileId())
                    .fileName(pdfFile.getName())
                    .filePath(pdfFile.getAbsolutePath())
                    .fileExtension(".pdf")
                    .build();

            FileProcessResult mainResult = documentFileProcessor.process(pdfRequest);

            List<EmbeddedObjectInfo> embeddedObjects = embeddedFileExtractService.extractAll(inputFile, extension);

            if (!embeddedObjects.isEmpty()) {
                log.info("[OFFICE] 총 {}개의 개체가 성공적으로 분석되었습니다. - file={}", embeddedObjects.size(), inputFile.getName());
            }

            mainResult.setEmbeddedObjects(embeddedObjects);
            mainResult.setHasEmbeddedObject(!embeddedObjects.isEmpty());

            return mainResult;
        } finally {
            if (pdfFile != null && pdfFile.exists()) {
                // pdfFile.delete();
            }
        }
    }

    private String normalizeExtension(String extension) {
        if (extension == null) return "";
        String normalized = extension.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith(".") ? normalized : "." + normalized;
    }
}