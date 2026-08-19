package com.filesearch.service;

import com.filesearch.model.ExtractedText;
import com.filesearch.model.FileMetadata;
import com.filesearch.model.FileStatus;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.repository.ExtractedTextRepository;
import com.filesearch.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.FileEntity;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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

}