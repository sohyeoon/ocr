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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.model.dto.FileProcessResult;
import com.filesearch.repository.FileMetadataRepository;
import com.filesearch.service.OleMappingService;
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
    private final FileMetadataRepository fileMetadataRepository;
    //private final OleMappingService oleMappingService;

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

            if ((".docx".equalsIgnoreCase(extension) || ".doc".equalsIgnoreCase(extension)) && !embeddedObjects.isEmpty()) {
                try {
                    RestTemplate restTemplate = new RestTemplate();
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                    body.add("file", new FileSystemResource(inputFile));

                    HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
                    String url = "http://localhost:8000/ole-pagenumber"; 
                    ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
                    if (response.getBody() != null) {
                    	List<Map<String, Object>> results =
                                (List<Map<String, Object>>) response.getBody().get("data");

                        updateEmbeddedParentPageNumbers(
                                embeddedObjects,
                                results
                        );
                    }
                    log.info("[OLE] API 매핑 업데이트 완료: {}", response.getBody());
                } catch (Exception e) {
                    log.error("[TEST-OLE] API 호출 중 오류 발생: {}", e.getMessage());
                }
            }

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
    
    private void updateEmbeddedParentPageNumbers(
            List<EmbeddedObjectInfo> embeddedObjects,
            List<Map<String, Object>> mappingResults
    ) {

        if (embeddedObjects == null || embeddedObjects.isEmpty()) {
            return;
        }

        if (mappingResults == null || mappingResults.isEmpty()) {
            log.info("[OLE] 페이지 매핑 결과가 없습니다.");
            return;
        }

        log.info(
                "[OLE] Embedded 객체 {}개와 페이지 매핑 시작",
                embeddedObjects.size()
        );

        for (Map<String, Object> result : mappingResults) {

            String oleName =
                    (String) result.get("ole_name");

            Integer pageNumber =
                    parsePageNumber(result.get("page"));

            if (oleName == null || pageNumber == null) {
                log.warn(
                        "[OLE] 잘못된 API 결과 스킵: oleName={}, page={}",
                        oleName,
                        pageNumber
                );
                continue;
            }

            /*
             * API의 ole_name과
             * EmbeddedObjectInfo의 oleName 비교
             */
            for (EmbeddedObjectInfo embeddedObject : embeddedObjects) {

                String embeddedOleName =
                        embeddedObject.getOleName();

                if (embeddedOleName == null) {
                    continue;
                }

                /*
                 * oleName이 같지 않으면 다음 Embedded 객체 확인
                 */
                if (!embeddedOleName.equals(oleName)) {
                    continue;
                }

                /*
                 * 같은 OLE 객체를 찾았으므로
                 * 해당 EmbeddedObjectInfo에 부모 페이지 번호 저장
                 */
                embeddedObject.setParentPageNumber(pageNumber);

                log.info(
                        "[OLE] 부모 페이지 매핑 성공: oleName={}, page={}",
                        oleName,
                        pageNumber
                );

                /*
                 * 현재 oleName에 해당하는 Embedded 객체를 찾았으므로
                 * 다음 mappingResults로 이동
                 */
                break;
            }
        }

        log.info("[OLE] Embedded 객체 페이지 매핑 완료");
    }
    
    private String extractEmbeddedFileName(
            String oleName
    ) {

        if (oleName == null || oleName.isBlank()) {
            return null;
        }

        String normalized =
                oleName.replace("\\", "/");

        int index =
                normalized.lastIndexOf("/");

        if (index < 0 || index == normalized.length() - 1) {
            return null;
        }

        return normalized.substring(index + 1);
    }
    
    private Integer parsePageNumber(Object page) {

        if (page == null) {
            return null;
        }

        if (page instanceof Number) {
            return ((Number) page).intValue();
        }

        try {
            return Integer.parseInt(
                    page.toString()
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }
}