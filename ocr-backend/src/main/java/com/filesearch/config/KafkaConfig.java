package com.filesearch.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${kafka.topics.file-process}")
    private String fileProcessTopic;

    @Value("${kafka.topics.file-result}")
    private String fileResultTopic;

    @Bean
    public NewTopic fileProcessTopic() {
        return TopicBuilder.name(fileProcessTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic fileResultTopic() {
        return TopicBuilder.name(fileResultTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}