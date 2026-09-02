package com.filesearch.service;

import java.io.File;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
         * extractWithPaddleOcr() 내용을 여기에 이동
         */

        // TODO 기존 OCR API 호출 코드 이동

        return "";
    }
}