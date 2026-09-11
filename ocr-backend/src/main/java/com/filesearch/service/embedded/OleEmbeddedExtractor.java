package com.filesearch.service.embedded;

import com.filesearch.model.dto.EmbeddedObjectInfo;
import lombok.extern.slf4j.Slf4j;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.DocumentNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.io.BufferedInputStream;
import java.io.InputStream;

@Slf4j
@Service
public class OleEmbeddedExtractor {

    /**
     * OLE Embedded 파일 추출
     *
     * 처리 순서
     *
     * 1. OLE2 파일이면 \1Ole10Native 탐색
     * 2. Ole10Native에서 원본 파일명 추출
     * 3. 원본 파일명이 있으면 그대로 사용
     * 4. 원본 파일명이 없을 때만 Magic Number로 확장자 추정
     * 5. extractedPath에 실제 추출 파일 저장
     */
	public EmbeddedObjectInfo extract(File embeddedFile) {

	    if (embeddedFile == null || !embeddedFile.exists()) {
	        throw new IllegalArgumentException(
	                "Embedded file does not exist: " + embeddedFile
	        );
	    }

	    try (InputStream inputStream =
	                 new BufferedInputStream(
	                         new FileInputStream(embeddedFile)
	                 )) {

	        FileMagic fileMagic =
	                FileMagic.valueOf(inputStream);

	        log.debug(
	                "Embedded file detected. file={}, magic={}",
	                embeddedFile.getName(),
	                fileMagic
	        );

	        if (fileMagic == FileMagic.OLE2) {
	            return extractFromOle2(embeddedFile);
	        }

	        return extractFromNativeStream(embeddedFile);

	    } catch (Exception e) {

	        log.error(
	                "Failed to extract embedded file: {}",
	                embeddedFile.getAbsolutePath(),
	                e
	        );

	        throw new RuntimeException(
	                "Failed to extract embedded file: "
	                        + embeddedFile.getName(),
	                e
	        );
	    }
	}

    /**
     * OLE2 Compound Document에서
     * \1Ole10Native stream을 찾는다.
     */
    private EmbeddedObjectInfo extractFromOle2(
            File file
    ) throws IOException {

        try (POIFSFileSystem fs =
                     new POIFSFileSystem(file)) {

            DirectoryNode root = fs.getRoot();

            /*
             * 일반적인 구조
             *
             * Root
             *  └ ObjectPool
             *      └ _xxxxxxx
             *          └ \1Ole10Native
             */
            if (root.hasEntry("ObjectPool")) {

                Entry objectPoolEntry =
                        root.getEntry("ObjectPool");

                if (objectPoolEntry instanceof DirectoryNode) {

                    DirectoryNode objectPool =
                            (DirectoryNode) objectPoolEntry;

                    EmbeddedObjectInfo result =
                            findOle10Native(objectPool);

                    if (result != null) {
                        return result;
                    }
                }
            }

            /*
             * ObjectPool이 없는 경우
             * Root부터 전체 재귀 탐색
             */
            EmbeddedObjectInfo result =
                    findOle10Native(root);

            if (result != null) {
                return result;
            }

            /*
             * \1Ole10Native을 찾지 못한 경우
             * OLE 파일 자체를 fallback 처리
             */
            byte[] data =
                    Files.readAllBytes(file.toPath());

            return createEmbeddedFile(
                    data,
                    null,
                    calculateHash(data)
            );
        }
    }

    /**
     * Directory 내부를 재귀적으로 탐색하여
     * \1Ole10Native stream을 찾는다.
     */
    private EmbeddedObjectInfo findOle10Native(
            DirectoryNode directory
    ) throws IOException {

        for (Entry entry : directory) {

            /*
             * POI에서 OLE Native Stream은
             * 일반적으로 \1Ole10Native이라는 이름을 가진다.
             */
            if ("\u0001Ole10Native".equalsIgnoreCase(
                    entry.getName()
            )) {

                if (entry instanceof DocumentNode) {

                    DocumentNode documentNode =
                            (DocumentNode) entry;

                    try (DocumentInputStream inputStream =
                                 new DocumentInputStream(
                                         documentNode
                                 )) {

                        byte[] nativeData =
                                inputStream.readAllBytes();

                        log.debug(
                                "Found Ole10Native. directory={}, size={}",
                                directory.getPath(),
                                nativeData.length
                        );

                        return createEmbeddedFile(
                                nativeData,
                                null,
                                calculateHash(nativeData)
                        );
                    }
                }
            }

            /*
             * 하위 Storage가 있으면 계속 탐색
             */
            if (entry instanceof DirectoryNode) {

                DirectoryNode childDirectory =
                        (DirectoryNode) entry;

                EmbeddedObjectInfo result =
                        findOle10Native(childDirectory);

                if (result != null) {
                    return result;
                }
            }
        }

        return null;
    }

    /**
     * OLE2가 아닌 native stream 처리
     */
    private EmbeddedObjectInfo extractFromNativeStream(
            File file
    ) throws IOException {

        byte[] nativeData =
                Files.readAllBytes(file.toPath());

        return createEmbeddedFile(
                nativeData,
                null,
                calculateHash(nativeData)
        );
    }

    /**
     * 실제 Embedded 파일 생성
     *
     * 가장 중요한 부분.
     *
     * ---------------------------------------------------------
     * 원본 파일명이 있는 경우
     * ---------------------------------------------------------
     *
     * Ole10Native
     *      ↓
     * getFileName()
     *      ↓
     * test.xlsx
     *      ↓
     * 그대로 사용
     *
     * 절대로
     *
     * test.xlsx + .xlsx
     *
     * 처리를 하지 않는다.
     *
     * ---------------------------------------------------------
     * 원본 파일명이 없는 경우
     * ---------------------------------------------------------
     *
     * Magic Number
     *      ↓
     * xlsx
     *      ↓
     * embedded_file.xlsx
     */
    private EmbeddedObjectInfo createEmbeddedFile(
            byte[] nativeData,
            String fallbackFileName,
            String rawHash
    ) throws IOException {

        if (nativeData == null || nativeData.length == 0) {
            throw new IOException(
                    "Embedded data is empty."
            );
        }

        String fileName = null;

        /*
         * Magic Number 결과는
         * 원본 파일명이 없을 때만 사용한다.
         */
        String detectedExtension = null;

        /*
         * =====================================================
         * 1. Ole10Native에서 원본 파일명 추출
         * =====================================================
         */
        try {

            Ole10Native ole10 =
                    new Ole10Native(
                            nativeData,
                            0
                    );

            String originalFileName =
                    ole10.getFileName();

            log.debug(
                    "Ole10Native original filename={}",
                    originalFileName
            );

            if (originalFileName != null
                    && !originalFileName.isBlank()) {

                /*
                 * 경로가 포함되어 있을 수 있으므로
                 * 실제 파일명만 가져온다.
                 *
                 * 예:
                 *
                 * C:\Users\test\test.xlsx
                 *      ↓
                 * test.xlsx
                 */
                fileName =
                        extractFileName(
                                originalFileName
                        );
            }

        } catch (Exception e) {

            /*
             * Ole10Native 구조가 아니거나
             * 파싱할 수 없는 경우 fallback
             */
            log.debug(
                    "Ole10Native parsing failed. "
                            + "Use fallback filename detection.",
                    e
            );
        }

        /*
         * =====================================================
         * 2. 원본 파일명이 없는 경우
         * =====================================================
         */
        if (fileName == null
                || fileName.isBlank()) {

            /*
             * 이때만 Magic Number 검사
             */
            detectedExtension =
                    determineExtensionByMagicNumber(
                            nativeData
                    );

            /*
             * fallback 파일명이 있으면 사용
             */
            if (fallbackFileName != null
                    && !fallbackFileName.isBlank()) {

                fileName =
                        extractFileName(
                                fallbackFileName
                        );
            }

            /*
             * fallback 파일명도 없으면
             * embedded_file.xxx 형태로 생성
             */
            if (fileName == null
                    || fileName.isBlank()) {

                if (detectedExtension != null
                        && !detectedExtension.isBlank()) {

                    fileName =
                            "embedded_file."
                                    + getExtension(
                                    detectedExtension
                            );

                } else {

                    fileName =
                            "embedded_file.bin";
                }
            }

            /*
             * fallback 파일명에는 확장자가 없고
             * Magic Number로 확장자를 알아낸 경우에만
             * 확장자를 추가한다.
             *
             * 예:
             *
             * embedded_file + xlsx
             *      ↓
             * embedded_file.xlsx
             */
            if (detectedExtension != null
                    && !detectedExtension.isBlank()
                    && !hasExtension(fileName)) {

                String cleanExt =
                        getExtension(
                                detectedExtension
                        );

                if (!cleanExt.isBlank()) {

                    fileName +=
                            "." + cleanExt;
                }
            }
        }

        /*
         * =====================================================
         * 3. 파일명 정리
         * =====================================================
         */
        fileName =
                sanitizeFileName(fileName);

        if (fileName == null
                || fileName.isBlank()) {

            fileName =
                    "embedded_file.bin";
        }

        /*
         * =====================================================
         * 4. 최종 확장자
         * =====================================================
         *
         * 원본 파일명이
         *
         * test.xlsx
         *
         * 라면
         *
         * fileExtension = xlsx
         *
         * 가 된다.
         */
        String fileExtension =
                getExtensionFromFileName(
                        fileName
                );

        /*
         * =====================================================
         * 5. 저장 디렉터리
         * =====================================================
         */
        Path outputDirectory =
                Paths.get(
                        System.getProperty(
                                "java.io.tmpdir"
                        ),
                        "embedded"
                );

        Files.createDirectories(
                outputDirectory
        );

        /*
         * UUID를 앞에 붙여서
         * 동일한 파일명이 여러 개 존재해도
         * 충돌하지 않도록 한다.
         *
         * 예:
         *
         * 7e5..._test.xlsx
         */
        String outputFileName =
                UUID.randomUUID()
                        + "_"
                        + fileName;

        Path outputPath =
                outputDirectory.resolve(
                        outputFileName
                );

        /*
         * 현재 기존 구조와 동일하게
         * 전달받은 nativeData를 저장한다.
         */
        Files.write(
                outputPath,
                nativeData
        );

        log.info(
                "Embedded file extracted. "
                        + "fileName={}, extension={}, path={}",
                fileName,
                fileExtension,
                outputPath
        );

        /*
         * =====================================================
         * 6. 실제 EmbeddedObjectInfo 생성
         * =====================================================
         */
        return EmbeddedObjectInfo.builder()
                .fileName(fileName)
                .fileExtension(fileExtension)
                .objectType("OLE")
                .extractedPath(
                        outputPath.toString()
                )
                .fileHash(rawHash)
                .build();
    }

    /**
     * 경로가 포함된 파일명에서
     * 실제 파일명만 추출한다.
     *
     * 예:
     *
     * C:\test\sample.xlsx
     *      -> sample.xlsx
     *
     * /tmp/sample.xlsx
     *      -> sample.xlsx
     */
    private String extractFileName(
            String fileName
    ) {

        if (fileName == null) {
            return null;
        }

        String normalized =
                fileName
                        .replace("\\", "/")
                        .trim();

        int lastSlash =
                normalized.lastIndexOf('/');

        if (lastSlash >= 0) {

            normalized =
                    normalized.substring(
                            lastSlash + 1
                    );
        }

        return normalized.trim();
    }

    /**
     * 확장자가 있는지 확인
     */
    private boolean hasExtension(
            String fileName
    ) {

        if (fileName == null
                || fileName.isBlank()) {

            return false;
        }

        int dotIndex =
                fileName.lastIndexOf('.');

        return dotIndex > 0
                && dotIndex < fileName.length() - 1;
    }

    /**
     * 파일명에서 확장자 추출
     *
     * test.xlsx -> xlsx
     */
    private String getExtensionFromFileName(
            String fileName
    ) {

        if (!hasExtension(fileName)) {
            return "";
        }

        int dotIndex =
                fileName.lastIndexOf('.');

        return fileName
                .substring(dotIndex + 1)
                .toLowerCase();
    }

    /**
     * 확장자 문자열 정리
     *
     * .xlsx -> xlsx
     * xlsx  -> xlsx
     */
    private String getExtension(
            String extension
    ) {

        if (extension == null) {
            return "";
        }

        return extension
                .trim()
                .replaceFirst("^\\.", "")
                .toLowerCase();
    }

    /**
     * 파일명에 사용할 수 없는 문자 제거
     */
    private String sanitizeFileName(
            String fileName
    ) {

        if (fileName == null) {
            return null;
        }

        String result =
                extractFileName(fileName);

        /*
         * Windows 파일명에서 사용할 수 없는 문자
         */
        result =
                result.replaceAll(
                        "[\\\\/:*?\"<>|]",
                        "_"
                );

        result =
                result.trim();

        /*
         * 지나치게 긴 파일명 방지
         */
        if (result.length() > 200) {

            result =
                    result.substring(
                            0,
                            200
                    );
        }

        return result;
    }

    /**
     * Magic Number를 이용한 확장자 추정
     */
    private String determineExtensionByMagicNumber(
            byte[] data
    ) {

        if (data == null
                || data.length < 4) {

            return "";
        }

        /*
         * PDF
         */
        if (startsWith(
                data,
                "%PDF".getBytes(
                        StandardCharsets.US_ASCII
                )
        )) {

            return "pdf";
        }

        /*
         * PNG
         */
        if (startsWith(
                data,
                new byte[]{
                        (byte) 0x89,
                        0x50,
                        0x4E,
                        0x47
                }
        )) {

            return "png";
        }

        /*
         * JPG
         */
        if (startsWith(
                data,
                new byte[]{
                        (byte) 0xFF,
                        (byte) 0xD8,
                        (byte) 0xFF
                }
        )) {

            return "jpg";
        }

        /*
         * ZIP / OOXML
         *
         * docx / xlsx / pptx
         */
        if (startsWith(
                data,
                new byte[]{
                        0x50,
                        0x4B,
                        0x03,
                        0x04
                }
        )) {

            return detectOoxmlExtension(data);
        }

        /*
         * OLE2 Compound File
         */
        if (startsWith(
                data,
                new byte[]{
                        (byte) 0xD0,
                        (byte) 0xCF,
                        0x11,
                        (byte) 0xE0
                }
        )) {

            return detectLegacyOleExtension(
                    data
            );
        }

        return "";
    }

    /**
     * OOXML 확장자 확인
     */
    private String detectOoxmlExtension(
            byte[] data
    ) {

        Path tempFile = null;

        try {

            tempFile =
                    Files.createTempFile(
                            "embedded_",
                            ".zip"
                    );

            Files.write(
                    tempFile,
                    data
            );

            try (ZipFile zipFile =
                         new ZipFile(
                                 tempFile.toFile()
                         )) {

                ZipEntry contentTypes =
                        zipFile.getEntry(
                                "[Content_Types].xml"
                        );

                if (contentTypes == null) {
                    return "zip";
                }

                String xml =
                        new String(
                                zipFile
                                        .getInputStream(
                                                contentTypes
                                        )
                                        .readAllBytes(),
                                StandardCharsets.UTF_8
                        );

                /*
                 * XLSX
                 */
                if (xml.contains(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )) {

                    return "xlsx";
                }

                /*
                 * PPTX
                 */
                if (xml.contains(
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                )) {

                    return "pptx";
                }

                /*
                 * DOCX
                 */
                if (xml.contains(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                )) {

                    return "docx";
                }

                return "zip";
            }

        } catch (Exception e) {

            log.debug(
                    "Failed to detect OOXML extension.",
                    e
            );

            return "zip";

        } finally {

            if (tempFile != null) {

                try {
                    Files.deleteIfExists(
                            tempFile
                    );
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Legacy Office 확장자 확인
     */
    private String detectLegacyOleExtension(
            byte[] data
    ) {

        Path tempFile = null;

        try {

            tempFile =
                    Files.createTempFile(
                            "embedded_",
                            ".ole"
                    );

            Files.write(
                    tempFile,
                    data
            );

            try (POIFSFileSystem fs =
                         new POIFSFileSystem(
                                 tempFile.toFile()
                         )) {

                DirectoryNode root =
                        fs.getRoot();

                /*
                 * Word
                 */
                if (root.hasEntry(
                        "WordDocument"
                )) {

                    return "doc";
                }

                /*
                 * Excel
                 */
                if (root.hasEntry(
                        "Workbook"
                )
                        || root.hasEntry(
                        "Book"
                )) {

                    return "xls";
                }

                /*
                 * PowerPoint
                 */
                if (root.hasEntry(
                        "PowerPoint Document"
                )) {

                    return "ppt";
                }
            }

        } catch (Exception e) {

            log.debug(
                    "Failed to detect legacy OLE extension.",
                    e
            );

        } finally {

            if (tempFile != null) {

                try {
                    Files.deleteIfExists(
                            tempFile
                    );
                } catch (IOException ignored) {
                }
            }
        }

        return "ole";
    }

    /**
     * byte[]가 prefix로 시작하는지 확인
     */
    private boolean startsWith(
            byte[] data,
            byte[] prefix
    ) {

        if (data.length < prefix.length) {
            return false;
        }

        for (int i = 0; i < prefix.length; i++) {

            if (data[i] != prefix[i]) {
                return false;
            }
        }

        return true;
    }

    /**
     * SHA-256
     */
    private String calculateHash(
            byte[] data
    ) {

        try {

            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] hash =
                    digest.digest(data);

            StringBuilder result =
                    new StringBuilder();

            for (byte b : hash) {

                result.append(
                        String.format(
                                "%02x",
                                b
                        )
                );
            }

            return result.toString();

        } catch (Exception e) {

            throw new RuntimeException(
                    "Failed to calculate SHA-256.",
                    e
            );
        }
    }
}