package com.filesearch.service.embedded;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.Ole10Native;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class OleEmbeddedExtractor {

    /**
     * OLE 객체를 분석하여 실제 Embedded 파일을 추출합니다.
     *
     * 지원 형태
     *
     * 1. OLE2 Compound File
     *    └─ \1Ole10Native
     *
     * 2. Package / \1Ole10Native stream
     *    └─ Ole10Native raw data
     *
     * 최종 결과:
     *    실제 파일명 + 실제 파일 데이터
     */
    public EmbeddedObjectInfo extract(File embeddedFile) throws Exception {

        if (embeddedFile == null || !embeddedFile.exists()) {
            throw new IllegalArgumentException(
                    "Embedded 파일이 존재하지 않습니다."
            );
        }

        FileMagic magic = FileMagic.valueOf(embeddedFile);

        log.info(
                "[OLE] 객체 분석 시작 - name={}, size={}, magic={}",
                embeddedFile.getName(),
                embeddedFile.length(),
                magic
        );

        /*
         * 실제 OLE2 Compound File
         *
         * 예:
         * oleObject1.bin
         *
         * 내부:
         *   Root
         *    └─ \1Ole10Native
         */
        if (magic == FileMagic.OLE2) {
            return extractFromOle2(embeddedFile);
        }

        /*
         * OLE2가 아닌 경우
         *
         * 현재 EmbeddedFileExtractService에서
         * Package 또는 \1Ole10Native stream을
         * 임시 파일로 저장해서 이쪽으로 전달할 수 있음.
         */
        return extractFromNativeStream(embeddedFile);
    }


    /**
     * OLE2 Compound Document에서
     * \1Ole10Native stream을 찾아 실제 파일을 추출합니다.
     */
    private EmbeddedObjectInfo extractFromOle2(File oleFile)
            throws Exception {

        try (POIFSFileSystem fs = new POIFSFileSystem(oleFile)) {

            DirectoryNode root = fs.getRoot();

            DocumentEntry nativeEntry =
                    findNativeStream(root);

            if (nativeEntry == null) {

                throw new IllegalArgumentException(
                        "OLE2 내부에서 \\1Ole10Native stream을 찾을 수 없습니다: "
                                + oleFile.getName()
                );
            }

            byte[] nativeData;

            try (DocumentInputStream dis =
                         new DocumentInputStream(nativeEntry)) {

                nativeData = dis.readAllBytes();
            }

            log.info(
                    "[OLE] \\1Ole10Native 발견 - {} bytes",
                    nativeData.length
            );

            return createEmbeddedFile(
                    nativeData,
                    oleFile.getParentFile()
            );
        }
    }


    /**
     * OLE2 내부를 재귀적으로 탐색하여
     * \1Ole10Native stream을 찾습니다.
     *
     * Root 바로 아래에 있을 수도 있고
     * Object Storage 내부에 있을 수도 있으므로
     * 재귀 탐색합니다.
     */
    private DocumentEntry findNativeStream(DirectoryNode directory) {

        for (org.apache.poi.poifs.filesystem.Entry entry : directory) {

            /*
             * 실제 stream
             */
            if (entry instanceof DocumentEntry documentEntry) {

                if (Ole10Native.OLE10_NATIVE.equals(
                        documentEntry.getName())) {

                    return documentEntry;
                }
            }

            /*
             * 하위 Storage
             */
            else if (entry instanceof DirectoryNode childDirectory) {

                DocumentEntry found =
                        findNativeStream(childDirectory);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }


    /**
     * Package / \1Ole10Native 등의
     * raw stream을 분석합니다.
     */
    private EmbeddedObjectInfo extractFromNativeStream(
            File streamFile) throws Exception {

        byte[] data =
                Files.readAllBytes(streamFile.toPath());

        if (data.length == 0) {

            throw new IllegalArgumentException(
                    "Embedded stream이 비어 있습니다: "
                            + streamFile.getName()
            );
        }

        log.info(
                "[OLE] Raw stream 분석 - {} bytes",
                data.length
        );

        return createEmbeddedFile(
                data,
                streamFile.getParentFile()
        );
    }


    /**
     * Ole10Native 구조를 파싱하고
     * 실제 embedded 파일을 생성합니다.
     */
    private EmbeddedObjectInfo createEmbeddedFile(
            byte[] nativeData,
            File outputDirectory) throws Exception {

        Ole10Native ole10 = null;

        try {
            ole10 = new Ole10Native(
                    nativeData,
                    0
            );
        } catch (Exception e) {
            log.warn("[OLE] Ole10Native 파싱 실패. Raw Binary 분석을 시도합니다. Error: {}", e.getMessage());
        }

        String fileName;
        byte[] fileData;

        if (ole10 != null) {
            String originalFileName = ole10.getFileName();
            fileName = extractFileName(originalFileName);
            fileData = ole10.getDataBuffer();
        } else {
            fileData = nativeData;
            fileName = determineExtensionByMagicNumber(fileData);
        }

        if (fileName == null || fileName.isBlank()) {
            fileName = "embedded_file.bin";
        }

        if (fileData == null || fileData.length == 0) {
            throw new IllegalArgumentException(
                    "임베디드 객체 내부 실제 파일 데이터가 없습니다."
            );
        }

        String detectedFullExt = determineExtensionByMagicNumber(fileData);
        String cleanExt = getExtension(detectedFullExt); 
        String dotExt = (cleanExt == null || cleanExt.isBlank()) ? ".bin" : "." + cleanExt;

        if (!fileName.toLowerCase().endsWith(dotExt.toLowerCase())) {
            fileName = fileName + dotExt;
        }

        Path outputPath =
                outputDirectory.toPath().resolve(fileName);

        if (Files.exists(outputPath)) {
            String extension = getExtension(fileName);
            String baseName = removeExtension(fileName);
            fileName = baseName + "_" + System.nanoTime() + (extension.isEmpty() ? "" : "." + extension);
            outputPath = outputDirectory.toPath().resolve(fileName);
        }

        Files.write(outputPath, fileData);

        log.info(
                "[OLE] 임베디드 파일 물리적 저장 완료: {} ({} bytes)",
                outputPath,
                fileData.length
        );

        return EmbeddedObjectInfo.builder()
                .fileName(fileName)
                .fileExtension(cleanExt)
                .objectType(ole10 != null ? "OLE" : "RAW_BINARY")
                .extractedPath(outputPath.toAbsolutePath().toString())
                .build();
    }

    private String determineExtensionByMagicNumber(byte[] data) {
        if (data == null || data.length < 4) {
            return "embedded_raw_" + System.currentTimeMillis() + ".bin";
        }

        if (data[0] == 0x25 && data[1] == 0x50 && data[2] == 0x44 && data[3] == 0x46) {
            return "embedded_pdf_" + System.currentTimeMillis() + ".pdf";
        }

        if (data[0] == (byte)0xD0 && data[1] == (byte)0xCF && data[2] == 0x11 && data[3] == (byte)0xE0) {
            return determineLegacyOleExtension(data);
        }

        if (data[0] == (byte)0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "embedded_img_" + System.currentTimeMillis() + ".png";
        }

        if (data[0] == (byte)0xFF && data[1] == (byte)0xD8 && data[2] == (byte)0xFF) {
            return "embedded_img_" + System.currentTimeMillis() + ".jpg";
        }

        if (data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04) {
            return determineOoxmlExtension(data);
        }

        return "embedded_raw_" + System.currentTimeMillis() + ".bin";
    }

    private String determineOoxmlExtension(byte[] data) {
        try {
            File tempFile = File.createTempFile("ooxml_detect_", ".tmp");
            Files.write(tempFile.toPath(), data);

            try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(tempFile)) {
                var entry = zipFile.getEntry("[Content_Types].xml");
                if (entry != null) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(zipFile.getInputStream(entry)))) {
                        String content = reader.lines().collect(java.util.stream.Collectors.joining());
                        if (content.contains("spreadsheetml.sheet")) {
                            return "embedded_xlsx_" + System.currentTimeMillis() + ".xlsx";
                        } else if (content.contains("presentationml.presentation")) {
                            return "embedded_pptx_" + System.currentTimeMillis() + ".pptx";
                        } else if (content.contains("wordprocessingml.document")) {
                            return "embedded_docx_" + System.currentTimeMillis() + ".docx";
                        }
                    }
                }
            } finally {
                tempFile.delete();
            }
        } catch (Exception e) {
            log.warn("[OLE] OOXML 세부 형식 판별 실패. 기본값 .xlsx 사용. Error: {}", e.getMessage());
        }
        return "embedded_office_" + System.currentTimeMillis() + ".xlsx";
    }

    private String determineLegacyOleExtension(byte[] data) {
        try {
            File tempFile = File.createTempFile("ole_detect_", ".tmp");
            Files.write(tempFile.toPath(), data);

            try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(tempFile)) {
                org.apache.poi.poifs.filesystem.DirectoryNode root = fs.getRoot();
                if (root.hasEntry("Workbook")) {
                    return "embedded_xls_" + System.currentTimeMillis() + ".xls";
                }
                if (root.hasEntry("PowerPoint Document") || root.hasEntry("\u0001PowerPoint Document")) {
                    return "embedded_ppt_" + System.currentTimeMillis() + ".ppt";
                }
                return "embedded_doc_" + System.currentTimeMillis() + ".doc";
            } finally {
                tempFile.delete();
            }
        } catch (Exception e) {
            log.warn("[OLE] Legacy OLE 세부 형식 판별 실패. 기본값 .doc 사용. Error: {}", e.getMessage());
            return "embedded_legacy_" + System.currentTimeMillis() + ".doc";
        }
    }


    /**
     * Ole10Native로 파싱되지 않는 경우
     *
     * 실제 데이터 자체가 파일일 가능성을 고려하여
     * 매직넘버를 확인합니다.
     */
    private EmbeddedObjectInfo extractRawBinary(
            byte[] data,
            File outputDirectory) throws IOException {

        String extension =
                detectExtension(data);

        String fileName =
                "embedded_" + System.nanoTime();

        if (extension != null) {
            fileName += "." + extension;
        } else {
            fileName += ".bin";
        }

        Path outputPath =
                createUniqueOutputPath(
                        outputDirectory,
                        fileName
                );

        Files.write(
                outputPath,
                data
        );

        log.warn(
                "[OLE] Raw Binary로 저장 - path={}, extension={}",
                outputPath,
                extension
        );

        return EmbeddedObjectInfo.builder()
                .fileName(
                        outputPath.getFileName().toString()
                )
                .fileExtension(
                        extension == null ? "bin" : extension
                )
                .objectType("RAW_BINARY")
                .extractedPath(
                        outputPath.toAbsolutePath().toString()
                )
                .build();
    }


    /**
     * 실제 파일 데이터의 확장자를 판별합니다.
     *
     * 주의:
     * ZIP이라고 무조건 xlsx가 아님.
     *
     * xlsx / docx / pptx 모두 ZIP 기반이므로
     * 여기서는 OOXML 내부 구조까지 확인하지 않고
     * 우선 zip으로 판별합니다.
     */
    private String detectExtension(byte[] data) {

        if (data == null || data.length < 4) {
            return null;
        }


        /*
         * PDF
         * %PDF
         */
        if (data[0] == 0x25
                && data[1] == 0x50
                && data[2] == 0x44
                && data[3] == 0x46) {

            return "pdf";
        }


        /*
         * PNG
         */
        if (data[0] == (byte) 0x89
                && data[1] == 0x50
                && data[2] == 0x4E
                && data[3] == 0x47) {

            return "png";
        }


        /*
         * JPEG
         */
        if (data[0] == (byte) 0xFF
                && data[1] == (byte) 0xD8
                && data[2] == (byte) 0xFF) {

            return "jpg";
        }


        /*
         * GIF
         */
        if (data[0] == 0x47
                && data[1] == 0x49
                && data[2] == 0x46) {

            return "gif";
        }


        /*
         * OLE2
         */
        if (data[0] == (byte) 0xD0
                && data[1] == (byte) 0xCF
                && data[2] == 0x11
                && data[3] == (byte) 0xE0) {

            return detectOle2Extension(data);
        }


        /*
         * ZIP / OOXML
         *
         * 여기서 xlsx라고 확정하지 않는다.
         */
        if (data[0] == 0x50
                && data[1] == 0x4B
                && data[2] == 0x03
                && data[3] == 0x04) {

            return "zip";
        }


        return null;
    }


    /**
     * Legacy OLE2 파일의 실제 Office 형식을 확인합니다.
     */
    private String detectOle2Extension(byte[] data) {

        File tempFile = null;

        try {

            tempFile =
                    File.createTempFile(
                            "ole_detect_",
                            ".tmp"
                    );

            Files.write(
                    tempFile.toPath(),
                    data
            );


            try (POIFSFileSystem fs =
                         new POIFSFileSystem(tempFile)) {

                DirectoryNode root =
                        fs.getRoot();


                /*
                 * Excel
                 */
                if (root.hasEntry("Workbook")) {
                    return "xls";
                }


                /*
                 * PowerPoint
                 */
                if (root.hasEntry("PowerPoint Document")
                        || root.hasEntry(
                                "\u0001PowerPoint Document"
                        )) {

                    return "ppt";
                }


                /*
                 * Word
                 */
                if (root.hasEntry("WordDocument")) {
                    return "doc";
                }
            }

        } catch (Exception e) {

            log.warn(
                    "[OLE] Legacy OLE 형식 판별 실패 - {}",
                    e.getMessage()
            );

        } finally {

            if (tempFile != null) {
                try {
                    Files.deleteIfExists(
                            tempFile.toPath()
                    );
                } catch (IOException ignored) {
                }
            }
        }


        return "ole";
    }


    /**
     * Windows 경로에서 실제 파일명만 추출합니다.
     */
    private String extractFileName(
            String originalPath) {

        if (originalPath == null
                || originalPath.isBlank()) {

            return null;
        }

        int index =
                Math.max(
                        originalPath.lastIndexOf('\\'),
                        originalPath.lastIndexOf('/')
                );

        if (index >= 0
                && index < originalPath.length() - 1) {

            return originalPath.substring(
                    index + 1
            );
        }

        return originalPath;
    }


    /**
     * 파일명에 확장자가 존재하는지 확인합니다.
     */
    private boolean hasExtension(
            String fileName) {

        if (fileName == null
                || fileName.isBlank()) {

            return false;
        }

        int index =
                fileName.lastIndexOf('.');

        return index > 0
                && index < fileName.length() - 1;
    }


    /**
     * 파일명이 이미 존재하면
     * _1, _2 형태로 새로운 이름을 생성합니다.
     */
    private Path createUniqueOutputPath(
            File outputDirectory,
            String fileName) {

        Path path =
                outputDirectory.toPath()
                        .resolve(fileName);

        if (!Files.exists(path)) {
            return path;
        }


        String extension =
                getExtension(fileName);

        String baseName =
                removeExtension(fileName);


        int count = 1;

        while (true) {

            String newFileName;

            if (extension.isBlank()) {

                newFileName =
                        baseName + "_" + count;

            } else {

                newFileName =
                        baseName
                                + "_"
                                + count
                                + "."
                                + extension;
            }


            path =
                    outputDirectory.toPath()
                            .resolve(newFileName);


            if (!Files.exists(path)) {
                return path;
            }

            count++;
        }
    }


    private String removeExtension(
            String fileName) {

        int index =
                fileName.lastIndexOf('.');

        if (index <= 0) {
            return fileName;
        }

        return fileName.substring(
                0,
                index
        );
    }


    private String getExtension(
            String fileName) {

        if (fileName == null
                || fileName.isBlank()) {

            return "";
        }

        int index =
                fileName.lastIndexOf('.');

        if (index == -1
                || index == fileName.length() - 1) {

            return "";
        }

        return fileName
                .substring(index + 1)
                .toLowerCase();
    }
}
