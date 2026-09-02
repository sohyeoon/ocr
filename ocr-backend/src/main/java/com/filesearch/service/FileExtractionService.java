package com.filesearch.service;

import com.filesearch.model.ExtractedText;
import com.filesearch.model.FileMetadata;
import com.filesearch.model.FileStatus;
import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.OfficeProcessResult;
import com.filesearch.repository.ExtractedTextRepository;
import com.filesearch.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.FileEntity;
import org.apache.poi.ooxml.POIXMLDocumentPart;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFObjectShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;


import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileExtractionService {

    private final Tika tika;
    private final FileMetadataRepository fileMetadataRepository;
    private final ExtractedTextRepository extractedTextRepository;
    private final OpenSearchService openSearchService;

    @Value("${paddle-ocr.api-url}")
    private String paddleOcrApiUrl;

    @Value("${paddle-ocr.enabled}")
    private boolean paddleOcrEnabled;

    /**
     * 파일에서 텍스트를 추출하고 DB 및 OpenSearch에 저장
     *
     * 추출 전략:
     * 1단계: 이미지 파일(.png, .jpg 등)은 바로 PaddleOCR로 처리
     * 2단계: 문서 파일(.pdf, .docx, .txt 등)은 먼저 Apache Tika로 추출 시도
     * 3단계: Tika 추출 결과가 비어있거나 실패하면 PaddleOCR로 재시도 (폴백)
     */
    @Transactional
    public void extractAndSave(FileProcessRequest request) {
        FileMetadata fileMetadata = fileMetadataRepository.findById(request.getFileId())
                .orElseThrow(() -> new RuntimeException("파일 메타데이터를 찾을 수 없습니다: " + request.getFileId()));

        try {
            fileMetadata.setStatus(FileStatus.PROCESSING);
            fileMetadataRepository.save(fileMetadata);

            String extractedContent = null;
            String extractionType = null;

            // ===== 1단계: 이미지 파일은 바로 PaddleOCR 사용 =====
            if (isImageFile(request.getFileExtension())) {
                log.info("[OCR] 이미지 파일 감지, PaddleOCR로 직접 처리: {}", request.getFileName());
                extractedContent = extractWithPaddleOcr(request.getFilePath());
                extractionType = "PADDLE_OCR";
            } else {
                // ===== 2단계: 문서 파일은 Tika로 먼저 시도 =====
                log.info("[TIKA] 문서 파일 텍스트 추출 시도: {}", request.getFileName());
                try {
                    extractedContent = extractWithTika(request.getFilePath());
                    extractionType = "TIKA";
                } catch (Exception tikaEx) {
                    log.warn("[TIKA] Tika 추출 실패: {} - 사유: {}", request.getFileName(), tikaEx.getMessage());
                    extractedContent = null;
                }

                // ===== 3단계: Tika 결과가 비어있거나 실패 시 PaddleOCR로 폴백 =====
                if ((extractedContent == null || extractedContent.trim().isEmpty()) && paddleOcrEnabled) {
                    log.info("[OCR 폴백] Tika 추출 결과 없음, PaddleOCR로 재시도: {}", request.getFileName());
                    String ocrContent = extractWithPaddleOcr(request.getFilePath());

                    if (ocrContent != null && !ocrContent.trim().isEmpty()) {
                        extractedContent = ocrContent;
                        extractionType = "PADDLE_OCR";
                        log.info("[OCR 폴백] PaddleOCR 추출 성공: {}", request.getFileName());
                    } else {
                        log.warn("[OCR 폴백] PaddleOCR도 추출 결과 없음: {}", request.getFileName());
                    }
                }
            }

            // ===== 결과 저장 =====
            if (extractedContent != null && !extractedContent.trim().isEmpty()) {
                // DB에 추출 텍스트 저장
                ExtractedText extractedText = ExtractedText.builder()
                        .fileMetadata(fileMetadata)
                        .content(extractedContent)
                        .extractionType(extractionType)
                        .build();
                extractedTextRepository.save(extractedText);

                // OpenSearch에 인덱싱
                openSearchService.indexDocument(fileMetadata, extractedContent);

                fileMetadata.setStatus(FileStatus.COMPLETED);
                fileMetadata.setProcessedAt(OffsetDateTime.now());
                log.info("[완료] 텍스트 추출 성공 (방식: {}): {}", extractionType, request.getFileName());
            } else {
                fileMetadata.setStatus(FileStatus.FAILED);
                fileMetadata.setErrorMessage("Tika 및 PaddleOCR 모두 텍스트 추출에 실패했습니다.");
                log.error("[실패] 모든 추출 방식 실패: {}", request.getFileName());
            }

        } catch (Exception e) {
            log.error("파일 텍스트 추출 중 예외 발생: {}", request.getFileName(), e);
            fileMetadata.setStatus(FileStatus.FAILED);
            fileMetadata.setErrorMessage(e.getMessage());
        }

        fileMetadataRepository.save(fileMetadata);
    }

    /**
     * Apache Tika를 사용하여 텍스트 추출
     */
    private String extractWithTika(String filePath) throws IOException, TikaException {
        File file = new File(filePath);
        return tika.parseToString(file);
    }

    /**
     * PaddleOCR REST API를 호출하여 텍스트 추출
     * multipart/form-data 방식으로 파일을 업로드하여 OCR 수행
     */
    private String extractWithPaddleOcr(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                log.error("PaddleOCR 대상 파일이 존재하지 않습니다: {}", filePath);
                return null;
            }

            RestTemplate restTemplate = new RestTemplate();  // Java에서 다른 서버에 HTTP 요청을 보내기 위한 객체 

            // multipart/form-data 요청 구성
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(file));

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(  // paddleocr url에 POST방식으로 데이터 보내고, 서버 응답 받아옴
                    paddleOcrApiUrl, requestEntity, Map.class);  // url로, requestEntity를, Map.class로 응답 받음

            if (response.getBody() != null && response.getBody().containsKey("texts")) {
                Object textObj = response.getBody().get("texts");
                if (textObj instanceof List) {
                    return String.join("\n", (List<String>) textObj);  // 리스트로 온 텍스트들을 개행문자 붙여서 하나의 문자열로 반환
                }
                return textObj.toString();
            }
        } catch (Exception e) {
            log.error("PaddleOCR 호출 실패 URL={}, FILE={}", paddleOcrApiUrl, filePath, e);
        }
        return null;
    }

    /**
     * 이미지 파일 여부 판단
     * 이미지 파일은 Tika로 텍스트 추출이 불가하므로 바로 PaddleOCR 사용
     */
    private boolean isImageFile(String extension) {
        if (extension == null) return false;
        return paddleOcrEnabled && (
                extension.equalsIgnoreCase(".png") || extension.equalsIgnoreCase(".jpg") ||
                        extension.equalsIgnoreCase(".jpeg") ||
                        extension.equalsIgnoreCase(".tiff") ||
                        extension.equalsIgnoreCase(".bmp") ||
                        extension.equalsIgnoreCase(".gif")
        );
    }

    /**
     * 추출된 파일 contents 삭제 (원본 아님)
     * 색인된 검색엔진 데이터 삭제
     */
    /*public void deleteFile(Long fileId) {

        try{
            extractedTextRepository.deleteFile(fileId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }*/
    
    // ---------- 개체 삽입 ------------------------------
    
    /**
     * 파일에서 텍스트를 추출하고 DB 및 OpenSearch에 저장
     *
     * 추출 전략:
     * 1단계: 이미지 파일(.png, .jpg 등)은 바로 PaddleOCR로 처리
     * 2단계: 문서 파일(.pdf, .docx, .txt 등)은 먼저 Apache Tika로 추출 시도
     * 3단계: Tika 추출 결과가 비어있거나 실패하면 PaddleOCR로 재시도 (폴백)
     */
    @Transactional
    public void processByFileType(FileProcessRequest request) {
        FileMetadata fileMetadata = fileMetadataRepository.findById(request.getFileId())
                .orElseThrow(() -> new RuntimeException("파일 메타데이터를 찾을 수 없습니다: " + request.getFileId()));

        try {
            fileMetadata.setStatus(FileStatus.PROCESSING);
            fileMetadataRepository.save(fileMetadata);

            String extractedContent = null;
            String extractionType = null;

            // ===== 1단계: 이미지 파일은 바로 PaddleOCR 사용 =====
            if (isImageFile(request.getFileExtension())) {
                log.info("[OCR] 이미지 파일 감지, PaddleOCR로 직접 처리: {}", request.getFileName());
                extractedContent = extractWithPaddleOcr(request.getFilePath());
                extractionType = "PADDLE_OCR";
            } else if (isOfficeFile(request.getFileExtension())){
            	log.info("[OFFICE] Office 파일 처리 시작: {}", request.getFileName());

                OfficeProcessResult result = processOfficeFile(request.getFilePath(), request.getFileExtension());

                extractedContent = result.getContent();
                extractionType = result.getExtractionType();
            } else {
                // ===== 2단계: 문서 파일은 Tika로 먼저 시도 =====
                log.info("[TIKA] 문서 파일 텍스트 추출 시도: {}", request.getFileName());
                try {
                    extractedContent = extractWithTika(request.getFilePath());
                    extractionType = "TIKA";
                } catch (Exception tikaEx) {
                    log.warn("[TIKA] Tika 추출 실패: {} - 사유: {}", request.getFileName(), tikaEx.getMessage());
                    extractedContent = null;
                }

                // ===== 3단계: Tika 결과가 비어있거나 실패 시 PaddleOCR로 폴백 =====
                if ((extractedContent == null || extractedContent.trim().isEmpty()) && paddleOcrEnabled) {
                    log.info("[OCR 폴백] Tika 추출 결과 없음, PaddleOCR로 재시도: {}", request.getFileName());
                    String ocrContent = extractWithPaddleOcr(request.getFilePath());

                    if (ocrContent != null && !ocrContent.trim().isEmpty()) {
                        extractedContent = ocrContent;
                        extractionType = "PADDLE_OCR";
                        log.info("[OCR 폴백] PaddleOCR 추출 성공: {}", request.getFileName());
                    } else {
                        log.warn("[OCR 폴백] PaddleOCR도 추출 결과 없음: {}", request.getFileName());
                    }
                }
            }

            // ===== 결과 저장 =====
            if (extractedContent != null && !extractedContent.trim().isEmpty()) {
                // DB에 추출 텍스트 저장
                ExtractedText extractedText = ExtractedText.builder()
                        .fileMetadata(fileMetadata)
                        .content(extractedContent)
                        .extractionType(extractionType)
                        .build();
                extractedTextRepository.save(extractedText);

                // OpenSearch에 인덱싱
                openSearchService.indexDocument(fileMetadata, extractedContent);

                fileMetadata.setStatus(FileStatus.COMPLETED);
                fileMetadata.setProcessedAt(OffsetDateTime.now());
                log.info("[완료] 텍스트 추출 성공 (방식: {}): {}", extractionType, request.getFileName());
            } else {
                fileMetadata.setStatus(FileStatus.FAILED);
                fileMetadata.setErrorMessage("Tika 및 PaddleOCR 모두 텍스트 추출에 실패했습니다.");
                log.error("[실패] 모든 추출 방식 실패: {}", request.getFileName());
            }

        } catch (Exception e) {
            log.error("파일 텍스트 추출 중 예외 발생: {}", request.getFileName(), e);
            fileMetadata.setStatus(FileStatus.FAILED);
            fileMetadata.setErrorMessage(e.getMessage());
        }

        fileMetadataRepository.save(fileMetadata);
    }
    
    private boolean isOfficeFile(String extension) {
        if (extension == null) {
            return false;
        }

        String ext = extension.toLowerCase();

        return Set.of(
                ".doc",
                ".docx",
                ".xls",
                ".xlsx",
                ".ppt",
                ".pptx"
        ).contains(ext);
    }
    
    private OfficeProcessResult processOfficeFile(
            String filePath,
            String fileExtension) throws Exception {

        String extension = normalizeExtension(fileExtension);

        log.info("[OFFICE] Office 파일 분석 시작: {}", filePath);
        
        OfficeProcessResult result = switch (extension) {

        case "docx" -> processDocx(filePath);

        case "pptx" -> processPptx(filePath);

        case "xlsx" -> processXlsx(filePath);

        default -> {
            log.warn(
                    "[OFFICE] 현재 지원하지 않는 Office 형식: {}",
                    extension
            );

            yield OfficeProcessResult.builder()
                    .content(extractWithTika(filePath))
                    .extractionType("TIKA")
                    .hasEmbeddedObject(false)
                    .embeddedObjects(Collections.emptyList())
                    .build();
        }
    };

    // 결과 확인
    log.info("[OFFICE] 추출 결과");
    log.info("[OFFICE] extractionType = {}", result.getExtractionType());
    log.info("[OFFICE] hasEmbeddedObject = {}", result.isHasEmbeddedObject());

    if (result.getEmbeddedObjects() != null) {

        log.info(
                "[OFFICE] embeddedObject 개수 = {}",
                result.getEmbeddedObjects().size()
        );

        for (EmbeddedObjectInfo object : result.getEmbeddedObjects()) {

            log.info(
                    "[OFFICE] Embedded Object - fileName={}, content={}, extension={}, objectType={}, extractedPath={}",
                    object.getFileName(),
                    object.getContent(),
                    object.getFileExtension(),
                    object.getObjectType(),
                    object.getExtractedPath()
            );
        }
    }

    return result;

//        return switch (extension) {
//
//            case "docx" -> processDocx(filePath);
//
//            case "pptx" -> processPptx(filePath);
//
//            case "xlsx" -> processXlsx(filePath);
//
//            default -> {
//                log.warn("[OFFICE] 현재 지원하지 않는 Office 형식: {}", extension);
//
//                yield OfficeProcessResult.builder()
//                        .content(extractWithTika(filePath))
//                        .extractionType("TIKA")
//                        .hasEmbeddedObject(false)
//                        .embeddedObjects(Collections.emptyList())
//                        .build();
//            }
//        };
    }
    
    // 파일 확장자를 정규화하여 소문자로 변환하고, 앞의 점(.) 제거
    private String normalizeExtension(String extension) {

        if (extension == null) {
            return "";
        }

        return extension
                .toLowerCase(Locale.ROOT)
                .replace(".", "");
    }
    
    private OfficeProcessResult processDocx(String filePath) throws Exception {

        log.info("[DOCX] DOCX 분석 시작: {}", filePath);

        List<EmbeddedObjectInfo> embeddedObjects = new ArrayList<>();

        StringBuilder content = new StringBuilder();

        try (FileInputStream fis = new FileInputStream(filePath);
             XWPFDocument document = new XWPFDocument(fis)) {

            // ==========================
            // 1. 일반 텍스트
            // ==========================

            for (XWPFParagraph paragraph : document.getParagraphs()) {

                String text = paragraph.getText();

                if (text != null && !text.isBlank()) {
                    content.append(text).append("\n");
                }
            }

            // ==========================
            // 2. 표
            // ==========================

            for (XWPFTable table : document.getTables()) {

                for (XWPFTableRow row : table.getRows()) {

                    for (XWPFTableCell cell : row.getTableCells()) {

                        String text = cell.getText();

                        if (text != null && !text.isBlank()) {
                            content.append(text).append("\t");
                        }
                    }

                    content.append("\n");
                }
            }

            // ==========================
            // 3. Embedded Object
            // ==========================

            List<PackagePart> embeddedParts =
                    findEmbeddedObjects(document);

            for (PackagePart part : embeddedParts) {

                String partName = part.getPartName().getName();

                String extension =
                        getExtension(partName);

                String objectType =
                        detectOfficeObjectType(part.getContentType(), extension);

                log.info(
                        "[EMBEDDED] DOCX 객체 발견 - name={}, type={}",
                        partName,
                        objectType
                );

                String extractedPath =
                        extractEmbeddedObject(part);

                embeddedObjects.add(
                        EmbeddedObjectInfo.builder()
                                .fileName(new File(partName).getName())
                                .fileExtension(extension)
                                .objectType(objectType)
                                .extractedPath(extractedPath)
                                .build()
                );
            }
        }

        return OfficeProcessResult.builder()
                .content(content.toString())
                .extractionType("APACHE_POI")
                .hasEmbeddedObject(!embeddedObjects.isEmpty())
                .embeddedObjects(embeddedObjects)
                .build();
    }
    
    private OfficeProcessResult processPptx(String filePath)
            throws Exception {

        log.info("[PPTX] PPTX 분석 시작: {}", filePath);

        StringBuilder content = new StringBuilder();

        List<EmbeddedObjectInfo> embeddedObjects =
                new ArrayList<>();

        try (FileInputStream fis =
                     new FileInputStream(filePath);
             XMLSlideShow ppt =
                     new XMLSlideShow(fis)) {

            int slideNumber = 1;

            for (XSLFSlide slide :
                    ppt.getSlides()) {

                content.append(
                        "\n[SLIDE ")
                        .append(slideNumber)
                        .append("]\n");

                // ==========================
                // Text
                // ==========================

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

                // ==========================
                // Embedded Object
                // ==========================

                for (XSLFShape shape :
                        slide.getShapes()) {

                    if (shape instanceof XSLFObjectShape objectShape) {

                        log.info(
                                "[EMBEDDED] PPTX 객체 발견 - slide={}",
                                slideNumber
                        );

                        // 우선 1단계에서는
                        // 객체가 존재한다는 것까지만 확인
                        embeddedObjects.add(
                                EmbeddedObjectInfo.builder()
                                        .fileName(
                                                "slide_" +
                                                slideNumber +
                                                "_embedded"
                                        )
                                        .fileExtension("")
                                        .objectType(
                                                "EMBEDDED_OBJECT"
                                        )
                                        .build()
                        );
                    }
                }

                slideNumber++;
            }
        }

        return OfficeProcessResult.builder()
                .content(content.toString())
                .extractionType("APACHE_POI")
                .hasEmbeddedObject(
                        !embeddedObjects.isEmpty()
                )
                .embeddedObjects(
                        embeddedObjects
                )
                .build();
    }
    
    private OfficeProcessResult processXlsx(String filePath)
            throws Exception {

        log.info("[XLSX] XLSX 분석 시작: {}", filePath);

        StringBuilder content = new StringBuilder();

        List<EmbeddedObjectInfo> embeddedObjects =
                new ArrayList<>();

        try (FileInputStream fis =
                     new FileInputStream(filePath);
             XSSFWorkbook workbook =
                     new XSSFWorkbook(fis)) {

            for (Sheet sheet :
                    workbook) {

                content.append(
                        "\n[SHEET ")
                        .append(sheet.getSheetName())
                        .append("]\n");

                for (Row row : sheet) {

                    for (Cell cell : row) {

                        String value =
                                getCellValue(cell);

                        if (value != null &&
                                !value.isBlank()) {

                            content.append(value)
                                    .append("\t");
                        }
                    }

                    content.append("\n");
                }

                // ==========================
                // Embedded Object
                // ==========================

                if (sheet instanceof XSSFSheet xssfSheet) {

                    for (POIXMLDocumentPart relation :
                            xssfSheet.getRelations()) {

                        String relationName =
                                relation.getClass()
                                        .getSimpleName();

                        if (relationName.contains("Object")) {

                            log.info(
                                    "[EMBEDDED] XLSX 객체 발견 - sheet={}",
                                    sheet.getSheetName()
                            );

                            embeddedObjects.add(
                                    EmbeddedObjectInfo.builder()
                                            .fileName(relationName)
                                            .fileExtension("")
                                            .objectType(
                                                    "EMBEDDED_OBJECT"
                                            )
                                            .build()
                            );
                        }
                    }
                }
            }
        }

        return OfficeProcessResult.builder()
                .content(content.toString())
                .extractionType("APACHE_POI")
                .hasEmbeddedObject(
                        !embeddedObjects.isEmpty()
                )
                .embeddedObjects(
                        embeddedObjects
                )
                .build();
    }
    
    private List<PackagePart> findEmbeddedObjects(
            XWPFDocument document) {

        List<PackagePart> result = new ArrayList<>();

        try {

            OPCPackage opcPackage = document.getPackage();

            for (PackagePart part : opcPackage.getParts()) {

                String name = part.getPartName().getName();

                if (name.contains("/embeddings/")) {  // 삽입 된 개체는 /embeddings/ 경로에 위치

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
    
    private String extractEmbeddedObject(
            PackagePart part) throws IOException {

        Path tempDirectory =
                Paths.get("temp", "embedded");

        Files.createDirectories(tempDirectory);

        String originalName =
                new File(
                        part.getPartName()
                                .getName()
                ).getName();

        String fileName =
                UUID.randomUUID() + "_" + originalName;

        Path outputPath =
                tempDirectory.resolve(fileName);

        try (InputStream inputStream =
                     part.getInputStream();
             OutputStream outputStream =
                     Files.newOutputStream(outputPath)) {

            inputStream.transferTo(outputStream);
        }

        log.info(
                "[EMBEDDED] 객체 파일 추출 완료: {}",
                outputPath
        );

        return outputPath.toString();
    }
    
    private String getExtension(String fileName) {

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
    
    private String detectOfficeObjectType(
            String contentType,
            String extension) {

        if (contentType == null) {
            return "UNKNOWN";
        }

        if (contentType.contains("spreadsheet")) {
            return "EXCEL";
        }

        if (contentType.contains("word")) {
            return "WORD";
        }

        if (contentType.contains("presentation")) {
            return "POWERPOINT";
        }

        if (contentType.contains("pdf")) {
            return "PDF";
        }

        if (extension.equals("xlsx") ||
                extension.equals("xls")) {
            return "EXCEL";
        }

        return "UNKNOWN";
    }
    
    private String getCellValue(
            org.apache.poi.ss.usermodel.Cell cell) {

        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {

            case STRING ->
                    cell.getStringCellValue();

            case NUMERIC ->
                    String.valueOf(
                            cell.getNumericCellValue()
                    );

            case BOOLEAN ->
                    String.valueOf(
                            cell.getBooleanCellValue()
                    );

            case FORMULA ->
                    cell.getCellFormula();

            default ->
                    "";
        };
    }
    
}