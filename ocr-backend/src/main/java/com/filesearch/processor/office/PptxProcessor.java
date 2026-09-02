package com.filesearch.processor.office;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.processor.FileProcessor;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class PptxProcessor implements FileProcessor {

    @Override
    public boolean supports(String extension) {

        return ".pptx".equalsIgnoreCase(extension);
    }

    @Override
    public FileProcessResult process(
            FileProcessRequest request) throws Exception {

        return processPptx(request);
    }

    /**
     * PPTX 분석
     *
     * 1. 슬라이드 텍스트 추출
     * 2. 표 텍스트 추출
     * 3. Embedded Object 발견
     */
    private FileProcessResult processPptx(
            FileProcessRequest request) throws Exception {

        String filePath = request.getFilePath();

        log.info(
                "[PPTX] PPTX 분석 시작: {}",
                filePath
        );

        StringBuilder content =
                new StringBuilder();

        List<EmbeddedObjectInfo> embeddedObjects =
                new ArrayList<>();

        try (
                FileInputStream fis =
                        new FileInputStream(filePath);

                XMLSlideShow ppt =
                        new XMLSlideShow(fis)
        ) {

            int slideNumber = 1;

            for (XSLFSlide slide : ppt.getSlides()) {

                content.append("\n")
                        .append("[SLIDE ")
                        .append(slideNumber)
                        .append("]\n");

                // =========================
                // 1. 슬라이드 텍스트
                // =========================

                extractSlideText(
                        slide,
                        content
                );

                // =========================
                // 2. 슬라이드 표
                // =========================

                extractSlideTables(
                        slide,
                        content
                );

                slideNumber++;
            }

            // =========================
            // 3. Embedded Object
            // =========================

            extractEmbeddedObjects(
                    ppt,
                    embeddedObjects
            );
        }

        log.info(
                "[PPTX] 텍스트 추출 완료 - length={}",
                content.length()
        );

        log.info(
                "[PPTX] Embedded Object 개수={}",
                embeddedObjects.size()
        );

        return FileProcessResult.builder()
                .content(content.toString())
                .extractionType("TEXT")
                .extractionMethod("APACHE_POI")
                .embeddedObjects(List.of())
                .embeddedObjects(
                        embeddedObjects
                )
                .build();
    }

    /**
     * 슬라이드의 일반 텍스트 추출
     */
    private void extractSlideText(
            XSLFSlide slide,
            StringBuilder content) {

        for (XSLFShape shape :
                slide.getShapes()) {

            if (shape instanceof XSLFTextShape textShape) {

                String text =
                        textShape.getText();

                if (text != null &&
                        !text.isBlank()) {

                    content.append(text)
                            .append("\n");
                }
            }
        }
    }

    /**
     * 슬라이드의 표 텍스트 추출
     */
    private void extractSlideTables(
            XSLFSlide slide,
            StringBuilder content) {

        for (XSLFShape shape :
                slide.getShapes()) {

            if (!(shape instanceof XSLFTable table)) {
                continue;
            }

            for (XSLFTableRow row :
                    table.getRows()) {

                for (XSLFTableCell cell :
                        row.getCells()) {

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
     * PPTX 내부 Embedded Object 검색
     *
     * PPTX 내부의 /embeddings/ 경로에
     * 삽입 객체가 저장되어 있는지 확인한다.
     */
    private void extractEmbeddedObjects(
            XMLSlideShow ppt,
            List<EmbeddedObjectInfo> embeddedObjects) {

        try {

            OPCPackage opcPackage =
                    ppt.getPackage();

            for (PackagePart part :
                    opcPackage.getParts()) {

                String partName =
                        part.getPartName()
                                .getName();

                if (!partName.contains(
                        "/embeddings/")) {

                    continue;
                }

                String extension =
                        getExtension(partName);

                String objectType =
                        detectOfficeObjectType(
                                part.getContentType(),
                                extension
                        );

                log.info(
                        "[EMBEDDED] PPTX 객체 발견 - name={}, type={}",
                        partName,
                        objectType
                );

                embeddedObjects.add(
                        EmbeddedObjectInfo.builder()
                                .fileName(
                                        getFileName(partName)
                                )
                                .fileExtension(
                                        extension
                                )
                                .objectType(
                                        objectType
                                )
                                .build()
                );
            }

        } catch (Exception e) {

            log.warn(
                    "[EMBEDDED] PPTX embedded object 검색 실패",
                    e
            );
        }
    }

    /**
     * 파일명 추출
     */
    private String getFileName(
            String path) {

        if (path == null) {
            return "";
        }

        int index =
                path.lastIndexOf('/');

        if (index == -1) {
            return path;
        }

        return path.substring(index + 1);
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

        if (contentType != null) {

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
        }

        if ("xlsx".equals(extension) ||
                "xls".equals(extension)) {

            return "EXCEL";
        }

        if ("docx".equals(extension) ||
                "doc".equals(extension)) {

            return "WORD";
        }

        if ("pptx".equals(extension) ||
                "ppt".equals(extension)) {

            return "POWERPOINT";
        }

        if ("pdf".equals(extension)) {
            return "PDF";
        }

        return "UNKNOWN";
    }
}