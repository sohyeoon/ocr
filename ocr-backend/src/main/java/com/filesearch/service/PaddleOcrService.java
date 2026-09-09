package com.filesearch.service;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PaddleOcrService {

    @Value("${paddle-ocr.api-url}")
    private String paddleOcrApiUrl;

    @Value("${paddle-ocr.enabled}")
    private boolean enabled;
    
    public boolean isEnabled() {
        return enabled;
    }

    public String extract(String filePath) throws Exception {

        if (!enabled) {
            log.warn("[PaddleOCR] OCR 기능이 비활성화되어 있습니다.");
            return "";
        }

        File file = new File(filePath);

        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "OCR 대상 파일이 존재하지 않습니다: " + filePath
            );
        }

        log.info(
                "[PaddleOCR] OCR 요청: {}",
                file.getAbsolutePath()
        );

        /*
         * 기존 FileExtractionService의
         * extractWithPaddleOcr() 내용
         */
        try {
            RestTemplate restTemplate = new RestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(file));

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    paddleOcrApiUrl, requestEntity, Map.class);

            if (response.getBody() != null && response.getBody().containsKey("texts")) {
                Object textObj = response.getBody().get("texts");
                if (textObj instanceof List) {
                    return String.join("\n", (List<String>) textObj);
                }
                return textObj.toString();
            }
        } catch (Exception e) {
            log.error("[PaddleOCR] 호출 실패 URL={}, FILE={}", paddleOcrApiUrl, filePath, e);
            throw e;
        }

        return "";
    }
}