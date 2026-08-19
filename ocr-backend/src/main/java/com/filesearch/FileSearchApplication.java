package com.filesearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FileSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileSearchApplication.class, args);
    }
}