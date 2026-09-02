package com.filesearch.service.embedded;

import java.io.File;
import java.nio.file.Files;

import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;

@Component
public class OleEmbeddedExtractor {

    public EmbeddedObjectInfo extract(
            File oleFile) throws Exception {

        try (POIFSFileSystem fs =
                     new POIFSFileSystem(oleFile)) {

            Ole10Native ole10 =
                    Ole10Native.createFromEmbeddedOleObject(fs);

            // ==========================================
            // 1. OLE 내부 원본 파일명
            // ==========================================

            String originalFileName =
                    ole10.getFileName();

            // ==========================================
            // 2. 실제 파일명만 추출
            // ==========================================

            String fileName =
                    extractFileName(
                            originalFileName
                    );

            // ==========================================
            // 3. 실제 파일 데이터
            // ==========================================

            byte[] data =
                    ole10.getDataBuffer();

            // ==========================================
            // 4. 임시 디렉터리에 실제 파일 생성
            // ==========================================

            File outputFile =
                    new File(
                            oleFile.getParentFile(),
                            fileName
                    );

            Files.write(
                    outputFile.toPath(),
                    data
            );

            // ==========================================
            // 5. 확장자
            // ==========================================

            String extension =
                    getExtension(fileName);

            return EmbeddedObjectInfo.builder()
                    .fileName(fileName)
                    .fileExtension(extension)
                    .objectType("OLE")
                    .extractedPath(
                            outputFile.getAbsolutePath()
                    )
                    .build();
        }
    }

    /**
     * OLE 내부의 원본 경로에서
     * 실제 파일명만 추출
     *
     * 예:
     *
     * S:\Downloads\test.pdf
     *      ↓
     * test.pdf
     *
     * C:\Users\ADMIN\test.xlsx
     *      ↓
     * test.xlsx
     *
     * test.pdf
     *      ↓
     * test.pdf
     */
    private String extractFileName(
            String originalPath) {

        if (originalPath == null ||
                originalPath.isBlank()) {

            return "embedded_file.bin";
        }

        // Windows 경로 \
        int windowsIndex =
                originalPath.lastIndexOf('\\');

        // Unix/Linux 경로 /
        int unixIndex =
                originalPath.lastIndexOf('/');

        int index =
                Math.max(
                        windowsIndex,
                        unixIndex
                );

        if (index >= 0 &&
                index < originalPath.length() - 1) {

            return originalPath.substring(
                    index + 1
            );
        }

        return originalPath;
    }

    /**
     * 확장자 추출
     */
    private String getExtension(
            String fileName) {

        if (fileName == null ||
                fileName.isBlank()) {

            return "";
        }

        int index =
                fileName.lastIndexOf('.');

        if (index == -1 ||
                index == fileName.length() - 1) {

            return "";
        }

        return fileName
                .substring(index + 1)
                .toLowerCase();
    }
}