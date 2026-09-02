package com.filesearch.processor.office;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.processor.FileProcessor;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class XlsxProcessor implements FileProcessor {

    @Override
    public boolean supports(String extension) {

        return ".xlsx".equalsIgnoreCase(extension);
    }

    @Override
    public FileProcessResult process(
            FileProcessRequest request) throws Exception {

        return processXlsx(request);
    }

    /**
     * XLSX 분석
     *
     * 1. Sheet별 셀 텍스트 추출
     * 2. Embedded Object 발견
     */
    private FileProcessResult processXlsx(
            FileProcessRequest request) throws Exception {

        String filePath =
                request.getFilePath();

        log.info(
                "[XLSX] XLSX 분석 시작: {}",
                filePath
        );

        StringBuilder content =
                new StringBuilder();

        List<EmbeddedObjectInfo> embeddedObjects =
                new ArrayList<>();

        try (
                FileInputStream fis =
                        new FileInputStream(filePath);

                XSSFWorkbook workbook =
                        new XSSFWorkbook(fis)
        ) {

            DataFormatter dataFormatter =
                    new DataFormatter();

            FormulaEvaluator evaluator =
                    workbook
                            .getCreationHelper()
                            .createFormulaEvaluator();

            // =========================
            // 1. Sheet 텍스트 추출
            // =========================

            for (Sheet sheet :
                    workbook) {

                extractSheet(
                        sheet,
                        content,
                        dataFormatter,
                        evaluator
                );
            }

            // =========================
            // 2. Embedded Object 검색
            // =========================

            extractEmbeddedObjects(
                    workbook,
                    embeddedObjects
            );
        }

        log.info(
                "[XLSX] 텍스트 추출 완료 - length={}",
                content.length()
        );

        log.info(
                "[XLSX] Embedded Object 개수={}",
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
     * Sheet 하나의 내용을 추출
     */
    private void extractSheet(
            Sheet sheet,
            StringBuilder content,
            DataFormatter dataFormatter,
            FormulaEvaluator evaluator) {

        content.append("\n")
                .append("[SHEET ")
                .append(sheet.getSheetName())
                .append("]\n");

        for (Row row :
                sheet) {

            boolean hasValue = false;

            for (Cell cell :
                    row) {

                String value =
                        getCellValue(
                                cell,
                                dataFormatter,
                                evaluator
                        );

                if (value != null &&
                        !value.isBlank()) {

                    content.append(value)
                            .append("\t");

                    hasValue = true;
                }
            }

            if (hasValue) {
                content.append("\n");
            }
        }
    }

    /**
     * Excel Cell 값 추출
     */
    private String getCellValue(
            Cell cell,
            DataFormatter dataFormatter,
            FormulaEvaluator evaluator) {

        if (cell == null) {
            return "";
        }

        try {

            return dataFormatter.formatCellValue(
                    cell,
                    evaluator
            );

        } catch (Exception e) {

            log.warn(
                    "[XLSX] Cell 값 추출 실패: {}",
                    cell.getAddress(),
                    e
            );

            return "";
        }
    }

    /**
     * XLSX 내부 Embedded Object 검색
     *
     * XLSX 내부의 /embeddings/ 경로에
     * 삽입 객체가 저장되어 있는지 확인한다.
     */
    private void extractEmbeddedObjects(
            XSSFWorkbook workbook,
            List<EmbeddedObjectInfo> embeddedObjects) {

        try {

            OPCPackage opcPackage =
                    workbook.getPackage();

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
                        "[EMBEDDED] XLSX 객체 발견 - name={}, type={}",
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
                    "[EMBEDDED] XLSX embedded object 검색 실패",
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