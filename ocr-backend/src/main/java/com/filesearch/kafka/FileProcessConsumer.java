package com.filesearch.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.filesearch.model.dto.FileProcessRequest;
import com.filesearch.service.FileExtractionService;
import com.filesearch.service.FileProcessingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileProcessConsumer {

    private final ObjectMapper objectMapper;
    private final FileExtractionService fileExtractionService;
    private final FileProcessingService fileProcessingService;

    /**
     * Kafka 토픽에서 파일 처리 요청을 수신하여 텍스트 추출 수행
     */
    @KafkaListener(topics = "${kafka.topics.file-process}", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeFileProcessRequest(String message) {
    	log.info("======================================");
        log.info("[KAFKA CONSUMER] 메시지 수신!");
        log.info("[KAFKA CONSUMER] message = {}", message);
        log.info("======================================");
        try {
            FileProcessRequest request = objectMapper.readValue(message, FileProcessRequest.class);
            log.info("파일 처리 요청 수신: {}", request.getFileName());

            //fileExtractionService.extractAndSave(request);
            
            //fileExtractionService.processByFileType(request);
            fileProcessingService.process(request);

            log.info("파일 처리 완료: {}", request.getFileName());
        } catch (Exception e) {
            log.error("파일 처리 중 오류 발생: {}", message, e);
        }
    }
}