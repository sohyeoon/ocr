package com.filesearch.processor.office;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.processor.FileProcessor;
import com.filesearch.service.embedded.EmbeddedFileExtractService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
@RequiredArgsConstructor
public class DocxProcessor implements FileProcessor {

	private final EmbeddedFileExtractService embeddedFileExtractService;
	
    @Override
    public boolean supports(String extension) {

        return ".docx".equalsIgnoreCase(extension);
    }

    @Override
    public FileProcessResult process(
            FileProcessRequest request) throws Exception {

        return processDocx(request);
    }

    /**
     * DOCX 분석
     *
     * 1. 일반 텍스트 추출
     * 2. 표 텍스트 추출
     * 3. Embedded Object 발견
     */
    private FileProcessResult processDocx(
            FileProcessRequest request) throws Exception {

        String filePath =
                request.getFilePath();

        log.info(
                "[DOCX] DOCX 분석 시작: {}",
                filePath
        );

        StringBuilder content =
                new StringBuilder();

        List<EmbeddedObjectInfo> embeddedObjects =
                new ArrayList<>();

        try (
                FileInputStream fis =
                        new FileInputStream(filePath);

                XWPFDocument document =
                        new XWPFDocument(fis)
        ) {

            // =========================
            // 1. 일반 텍스트
            // =========================

            extractParagraphs(
                    document,
                    content
            );

            // =========================
            // 2. 표
            // =========================

            extractTables(
                    document,
                    content
            );

            // =========================
            // 3. Embedded Object
            // =========================

            extractEmbeddedObjects(
                    document,
                    embeddedObjects
            );
        }

        log.info(
                "[DOCX] 텍스트 추출 완료 - length={}",
                content.length()
        );

        log.info(
                "[DOCX] Embedded Object 개수={}",
                embeddedObjects.size()
        );

        return FileProcessResult.builder()
                .content(content.toString())
                .extractionType("TEXT")
                .extractionMethod("APACHE_POI")
                .embeddedObjects(
                        embeddedObjects
                )
                .build();
    }

    /**
     * 일반 문단 텍스트 추출
     */
    private void extractParagraphs(
            XWPFDocument document,
            StringBuilder content) {

        for (XWPFParagraph paragraph :
                document.getParagraphs()) {

            String text =
                    paragraph.getText();

            if (text != null &&
                    !text.isBlank()) {

                content.append(text)
                        .append("\n");
            }
        }
    }

    /**
     * 표 내부 텍스트 추출
     */
    private void extractTables(
            XWPFDocument document,
            StringBuilder content) {

        for (XWPFTable table :
                document.getTables()) {

            for (XWPFTableRow row :
                    table.getRows()) {

                for (XWPFTableCell cell :
                        row.getTableCells()) {

                    String text =
                            cell.getText();

                    if (text != null &&
                            !text.isBlank()) {

                        content.append(text)
                                .append("\t");
                    }
                }

                content.append("\n");
            }
        }
    }


    /**
     * DOCX 내부 Embedded Object 검색 및 실제 파일 추출
     */
    private void extractEmbeddedObjects(
            XWPFDocument document,
            List<EmbeddedObjectInfo> embeddedObjects)
            throws Exception {

        List<PackagePart> embeddedParts =
                findEmbeddedObjects(document);

        for (PackagePart part :
                embeddedParts) {

            String partName =
                    part.getPartName()
                            .getName();

            String extension =
                    getExtension(partName);

            String objectType =
                    detectOfficeObjectType(
                            part.getContentType(),
                            extension
                    );

            log.info(
                    "[EMBEDDED] DOCX 객체 발견 - name={}, type={}, contentType={}",
                    partName,
                    objectType,
                    part.getContentType()
            );

            // ==========================================
            // 1. PackagePart → 실제 임시 파일
            // ==========================================

            File extractedPartFile =
                    savePackagePartToTempFile(
                            part,
                            extension
                    );

            log.info(
                    "[EMBEDDED] PackagePart 임시 파일 생성: {}",
                    extractedPartFile.getAbsolutePath()
            );

            // ==========================================
            // 2. OLE / OOXML 실제 파일 추출
            // ==========================================

            
			EmbeddedObjectInfo extractedInfo =
                    embeddedFileExtractService.extract(
                            extractedPartFile
                    );

            if (extractedInfo == null) {

                log.warn(
                        "[EMBEDDED] Embedded 파일 추출 결과가 null입니다: {}",
                        partName
                );

                continue;
            }

            // ==========================================
            // 3. DOCX에서 발견한 정보 보완
            // ==========================================

            EmbeddedObjectInfo finalInfo =
                    EmbeddedObjectInfo.builder()
                            .fileName(
                                    extractedInfo.getFileName()
                            )
                            .fileExtension(
                                    extractedInfo.getFileExtension()
                            )
                            .objectType(
                                    extractedInfo.getObjectType()
                            )
                            .extractedPath(
                                    extractedInfo.getExtractedPath()
                            )
                            .build();

            embeddedObjects.add(finalInfo);

            log.info(
                    "[EMBEDDED] 실제 파일 추출 완료 - fileName={}, path={}",
                    finalInfo.getFileName(),
                    finalInfo.getExtractedPath()
            );
        }
    }
    

    /**
     * DOCX 내부 PackagePart를 실제 임시 파일로 저장
     */
    private File savePackagePartToTempFile(
            PackagePart part,
            String extension)
            throws IOException {

        Path tempDirectory =
                Path.of(
                        System.getProperty("java.io.tmpdir"),
                        "file-search-system",
                        "embedded"
                );

        Files.createDirectories(
                tempDirectory
        );

        String safeExtension =
                extension == null ||
                extension.isBlank()
                        ? "bin"
                        : extension;

        Path outputPath =
                tempDirectory.resolve(
                        UUID.randomUUID()
                                + "."
                                + safeExtension
                );

        try (
                InputStream inputStream =
                        part.getInputStream()
        ) {

            Files.copy(
                    inputStream,
                    outputPath,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }

        return outputPath.toFile();
    }


    /**
     * DOCX 내부 /embeddings/ 경로의 객체 검색
     */
    private List<PackagePart> findEmbeddedObjects(
            XWPFDocument document) {

        List<PackagePart> result =
                new ArrayList<>();

        try {

            OPCPackage opcPackage =
                    document.getPackage();

            for (PackagePart part :
                    opcPackage.getParts()) {

                String name =
                        part.getPartName()
                                .getName();

                if (name.contains(
                        "/embeddings/")) {

                    result.add(part);
                }
            }

        } catch (Exception e) {

            log.warn(
                    "[EMBEDDED] DOCX embedded object 검색 실패",
                    e
            );
        }

        return result;
    }

    /**
     * 확장자 추출
     */
    private String getExtension(
            String fileName) {

        if (fileName == null) {
            return "";
        }

        int index =
                fileName.lastIndexOf('.');

        if (index == -1) {
            return "";
        }

        return fileName
                .substring(index + 1)
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Embedded Object 유형 판단
     */
    private String detectOfficeObjectType(
            String contentType,
            String extension) {

        if (contentType == null) {
            return "UNKNOWN";
        }

        if (contentType.contains(
                "spreadsheet")) {

            return "EXCEL";
        }

        if (contentType.contains("word")) {
            return "WORD";
        }

        if (contentType.contains(
                "presentation")) {

            return "POWERPOINT";
        }

        if (contentType.contains("pdf")) {
            return "PDF";
        }

        if ("xlsx".equals(extension) ||
                "xls".equals(extension)) {

            return "EXCEL";
        }

        return "UNKNOWN";
    }
}