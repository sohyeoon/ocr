package com.filesearch.service.embedded;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import com.filesearch.model.dto.EmbeddedObjectInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedFileExtractService {

    private final OleEmbeddedExtractor oleEmbeddedExtractor;
    private final OoxmlEmbeddedExtractor ooxmlEmbeddedExtractor;


    /**
     * Office 파일의 모든 Embedded Object를 추출하고
     * 실제 파일 분석 결과를 반환합니다.
     */
    public List<EmbeddedObjectInfo> extractAll(
            File file,
            String extension) {

        List<EmbeddedObjectInfo> results =
            new ArrayList<>();

        try {

            /*
             * DOCX
             */
            if (".docx".equalsIgnoreCase(extension)) {

                List<File> rawFiles =
                    extractRawFromDocx(file);

                for (File rawFile : rawFiles) {

                    try {

                        EmbeddedObjectInfo info =
                            extract(rawFile);

                        if (info != null) {
                            results.add(info);
                        }

                    } catch (Exception e) {

                        log.warn(
                            "[EmbeddedService] DOCX Embedded 분석 실패: {} - {}",
                            rawFile.getName(),
                            e.getMessage()
                        );
                    }
                }
            }

            /*
             * DOC
             */
            else if (".doc".equalsIgnoreCase(extension)) {

                results.addAll(
                    extractEmbeddedFromDoc(file)
                );
            }

            else {

                log.debug(
                    "[EmbeddedService] 지원하지 않는 확장자: {}",
                    extension
                );
            }

        } catch (Exception e) {

            log.error(
                "[EmbeddedService] Embedded 처리 실패: {}",
                file.getName(),
                e
            );
        }

        return results;
    }


    /**
     * 하나의 Embedded 파일을 실제 파일 형식에 따라 처리합니다.
     *
     * 파일 이름의 .bin 여부가 아니라
     * 실제 Magic Number를 기준으로 판단합니다.
     */
    public EmbeddedObjectInfo extract(
            File embeddedFile) throws Exception {

        if (embeddedFile == null ||
            !embeddedFile.exists()) {

            throw new IllegalArgumentException(
                "Embedded 파일이 존재하지 않습니다."
            );
        }

        FileMagic magic =
            FileMagic.valueOf(embeddedFile);

        log.debug(
            "[EmbeddedService] Embedded 분석: name={}, magic={}",
            embeddedFile.getName(),
            magic
        );

        return switch (magic) {

            case OLE2 ->
                oleEmbeddedExtractor.extract(
                    embeddedFile
                );

            case OOXML ->
                ooxmlEmbeddedExtractor.extract(
                    embeddedFile
                );

            default ->
                /*
                 * .bin이어도 Package stream일 수 있으므로
                 * OleEmbeddedExtractor에서 직접 파싱을 시도합니다.
                 */
                oleEmbeddedExtractor.extract(
                    embeddedFile
                );
        };
    }


    /**
     * DOCX 내부의 /embeddings/ 영역을 추출합니다.
     */
    private List<File> extractRawFromDocx(
            File file) throws Exception {

        List<File> rawFiles =
            new ArrayList<>();

        try (
            FileInputStream fis =
                new FileInputStream(file);

            XWPFDocument doc =
                new XWPFDocument(fis)
        ) {

            for (
                PackagePart part :
                doc.getPackage().getParts()
            ) {

                String partName =
                    part.getPartName().getName();

                if (!partName.contains("/embeddings/")) {
                    continue;
                }

                log.debug(
                    "[EmbeddedService] DOCX Embedded 발견: {}",
                    partName
                );

                rawFiles.add(
                    savePartToFile(part)
                );
            }
        }

        return rawFiles;
    }


    /**
     * DOC(OLE2)에서 Embedded Object를 추출합니다.
     */
    private List<EmbeddedObjectInfo> extractEmbeddedFromDoc(
            File file) throws Exception {

        List<EmbeddedObjectInfo> results =
            new ArrayList<>();

        try (
            POIFSFileSystem fs =
                new POIFSFileSystem(file)
        ) {

            DirectoryEntry root =
                fs.getRoot();

            if (!root.hasEntry("ObjectPool")) {

                log.debug(
                    "[EmbeddedService] ObjectPool 없음: {}",
                    file.getName()
                );

                return results;
            }

            Entry objectPoolEntry =
                root.getEntry("ObjectPool");

            if (!(objectPoolEntry
                    instanceof DirectoryEntry objectPool)) {

                return results;
            }

            for (Entry entry : objectPool) {

                if (!(entry
                        instanceof DirectoryEntry storage)) {
                    continue;
                }

                log.debug(
                    "[EmbeddedService] OLE Storage 발견: {}",
                    storage.getName()
                );

                /*
                 * 하나의 Storage에서
                 * 실제 Embedded 파일 하나를 추출합니다.
                 */
                EmbeddedObjectInfo info =
                    extractFromStorage(storage);

                if (info != null) {
                    results.add(info);
                }
            }
        }

        return results;
    }


    /**
     * 하나의 OLE Storage에서 실제 Embedded 파일을 추출합니다.
     *
     * 우선순위:
     *
     * 1. \1Ole10Native
     * 2. Package
     * 3. CONTENTS
     */
    private EmbeddedObjectInfo extractFromStorage(
            DirectoryEntry storage) throws Exception {

        DocumentEntry ole10Native = null;
        DocumentEntry packageEntry = null;
        DocumentEntry contentsEntry = null;

        for (Entry entry : storage) {

            if (!(entry instanceof DocumentEntry documentEntry)) {
                continue;
            }

            String name =
                documentEntry.getName();

            log.debug(
                "[EmbeddedService] OLE Stream: {} / {}",
                storage.getName(),
                name
            );

            if ("\u0001Ole10Native"
                    .equalsIgnoreCase(name)) {

                ole10Native = documentEntry;

            } else if ("Package"
                    .equalsIgnoreCase(name)) {

                packageEntry = documentEntry;

            } else if ("CONTENTS"
                    .equalsIgnoreCase(name)) {

                contentsEntry = documentEntry;
            }
        }

        /*
         * 1. Ole10Native
         */
        if (ole10Native != null) {

            File tempFile =
                saveDocumentEntry(
                    ole10Native,
                    "ole10native"
                );

            return oleEmbeddedExtractor.extract(
                tempFile
            );
        }

        /*
         * 2. Package
         */
        if (packageEntry != null) {

            File tempFile =
                saveDocumentEntry(
                    packageEntry,
                    "package"
                );

            return oleEmbeddedExtractor.extract(
                tempFile
            );
        }

        /*
         * 3. CONTENTS
         */
        if (contentsEntry != null) {

            File tempFile =
                saveDocumentEntry(
                    contentsEntry,
                    "contents"
                );

            return oleEmbeddedExtractor.extract(
                tempFile
            );
        }

        log.debug(
            "[EmbeddedService] Embedded 데이터 Stream 없음: {}",
            storage.getName()
        );

        return null;
    }


    /**
     * DOCX PackagePart 저장
     */
    private File savePartToFile(
            PackagePart part) throws IOException {

        Path tempDir =
            Paths.get("temp", "embedded");

        Files.createDirectories(tempDir);

        String originalName =
            Paths.get(
                part.getPartName().getName()
            ).getFileName().toString();

        File tempFile =
            tempDir.resolve(
                UUID.randomUUID()
                + "_"
                + originalName
            ).toFile();

        try (
            InputStream is =
                part.getInputStream();

            OutputStream os =
                Files.newOutputStream(
                    tempFile.toPath()
                )
        ) {

            is.transferTo(os);
        }

        return tempFile;
    }


    /**
     * OLE Stream을 임시 파일로 저장합니다.
     *
     * 실제 확장자는 중요하지 않습니다.
     * OleEmbeddedExtractor가 stream의 내용을 파싱하여
     * 실제 파일명을 복원합니다.
     */
    private File saveDocumentEntry(
            DocumentEntry documentEntry,
            String type) throws IOException {

        Path tempDir =
            Paths.get("temp", "embedded");

        Files.createDirectories(tempDir);

        File tempFile =
            tempDir.resolve(
                UUID.randomUUID()
                + "_"
                + type
                + ".bin"
            ).toFile();

        try (
            DocumentInputStream dis =
                new DocumentInputStream(
                    documentEntry
                );

            OutputStream os =
                Files.newOutputStream(
                    tempFile.toPath()
                )
        ) {

            dis.transferTo(os);
        }

        return tempFile;
    }
}
