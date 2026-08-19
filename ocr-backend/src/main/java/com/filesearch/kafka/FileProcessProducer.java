package com.filesearch.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.filesearch.model.dto.FileProcessRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileProcessProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${kafka.topics.file-process}")
    private String fileProcessTopic;

    /**
     * 파일 처리 요청을 Kafka 토픽에 전송
     */
    public void sendFileProcessRequest(FileProcessRequest request) {
        try {
            String message = objectMapper.writeValueAsString(request);
            kafkaTemplate.send(fileProcessTopic, request.getFilePath(), message)
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            log.info("파일 처리 요청 전송 성공: {}", request.getFileName());
                        } else {
                            log.error("파일 처리 요청 전송 실패: {}", request.getFileName(), ex);
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("메시지 직렬화 실패: {}", request.getFileName(), e);
        }
    }
}