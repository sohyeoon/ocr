package com.filesearch.service;

import java.io.File;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TikaService {

    private final Tika tika;

    public String extract(String filePath) throws Exception {

        File file = new File(filePath);

        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "파일이 존재하지 않습니다: " + filePath
            );
        }

        log.info(
                "[TIKA] 파일 텍스트 추출: {}",
                file.getAbsolutePath()
        );

        return tika.parseToString(file);
    }
}