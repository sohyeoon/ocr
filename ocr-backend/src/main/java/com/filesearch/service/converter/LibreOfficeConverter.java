package com.filesearch.service.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
public class LibreOfficeConverter {

    @Value("${gotenberg.url}")
    private String gotenbergUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public File convertToPdf(File inputFile) throws Exception {
        log.info("[CONVERTER] Converting to PDF via Gotenberg: {}", inputFile.getName());

        // 1. Multipart Request 생성
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("files", new FileSystemResource(inputFile));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            // 2. Gotenberg API 호출
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    gotenbergUrl,
                    HttpMethod.POST,
                    requestEntity,
                    byte[].class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                // 3. 응답 받은 PDF 바이너리를 파일로 저장
                String baseName = inputFile.getName().substring(0, inputFile.getName().lastIndexOf('.'));
                Path outputPath = inputFile.getParentFile().toPath().resolve(baseName + ".pdf");
                
                Files.write(outputPath, response.getBody());
                
                log.info("[CONVERTER] PDF conversion successful: {}", outputPath.toString());
                return outputPath.toFile();
            } else {
                throw new RuntimeException("Gotenberg conversion failed with status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[CONVERTER] Error during Gotenberg API call: {}", e.getMessage());
            throw e;
        }
    }
}
