package com.filesearch.service.embedded;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.filesearch.model.dto.EmbeddedObjectInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedFileExtractService {

    private static final String WORD_NS =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private static final String VML_NS =
            "urn:schemas-microsoft-com:vml";

    private static final String OFFICE_NS =
            "urn:schemas-microsoft-com:office:office";

    private static final String REL_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private static final String PACKAGE_REL_NS =
            "http://schemas.openxmlformats.org/package/2006/relationships";


    private final OleEmbeddedExtractor oleEmbeddedExtractor;

    private final OoxmlEmbeddedExtractor ooxmlEmbeddedExtractor;


    /**
     * Office 파일의 모든 Embedded Object를 추출한다.
     */
    public List<EmbeddedObjectInfo> extractAll(
            File file,
            String extension) {

        log.info(
                "[EmbeddedService] Embedded 분석 시작 - file={}, extension={}",
                file.getName(),
                extension
        );

        try {

            if (".docx".equalsIgnoreCase(extension)) {

                return extractEmbeddedFromDocx(file);

            } else if (".doc".equalsIgnoreCase(extension)) {

                return extractEmbeddedFromDoc(file);

            }

            log.debug(
                    "[EmbeddedService] 지원하지 않는 확장자: {}",
                    extension
            );

        } catch (Exception e) {

            log.error(
                    "[EmbeddedService] Embedded 처리 실패: {}",
                    file.getName(),
                    e
            );
        }

        return new ArrayList<>();
    }


    /**
     * ============================================================
     * DOCX
     * ============================================================
     *
     * Python의 extract_ole_preview_pairs()와 동일한 역할.
     *
     * document.xml
     *   └─ w:object
     *        ├─ v:imagedata r:id
     *        └─ o:OLEObject r:id
     *
     * document.xml.rels
     *   ├─ preview rId → word/media/...
     *   └─ ole rId     → word/embeddings/...
     */
    private List<EmbeddedObjectInfo> extractEmbeddedFromDocx(
            File file) throws Exception {

        List<EmbeddedObjectInfo> results =
                new ArrayList<>();

        try (ZipFile zipFile = new ZipFile(file)) {

            ZipEntry documentEntry =
                    zipFile.getEntry("word/document.xml");

            ZipEntry relsEntry =
                    zipFile.getEntry(
                            "word/_rels/document.xml.rels"
                    );

            if (documentEntry == null) {

                log.warn(
                        "[EmbeddedService] word/document.xml 없음: {}",
                        file.getName()
                );

                return results;
            }

            if (relsEntry == null) {

                log.warn(
                        "[EmbeddedService] document.xml.rels 없음: {}",
                        file.getName()
                );

                return results;
            }


            /*
             * --------------------------------------------------------
             * 1. document.xml 파싱
             * --------------------------------------------------------
             */
            Document documentXml;

            try (InputStream is =
                         zipFile.getInputStream(documentEntry)) {

                documentXml =
                        parseXml(is);
            }


            /*
             * --------------------------------------------------------
             * 2. document.xml.rels 파싱
             * --------------------------------------------------------
             */
            Document relsXml;

            try (InputStream is =
                         zipFile.getInputStream(relsEntry)) {

                relsXml =
                        parseXml(is);
            }


            /*
             * --------------------------------------------------------
             * 3. rId → target 관계 생성
             *
             * Python:
             *
             * rid_to_target[rid] = "word/" + target
             * --------------------------------------------------------
             */
            Map<String, String> ridToTarget =
                    buildRelationshipMap(relsXml);


            /*
             * --------------------------------------------------------
             * 4. document.xml 안의 w:object 순회
             *
             * Python:
             *
             * for obj_elem in doc_root.iter("{w}object"):
             * --------------------------------------------------------
             */
            NodeList objectNodes =
                    documentXml.getElementsByTagNameNS(
                            WORD_NS,
                            "object"
                    );

            int order = 0;

            for (int i = 0;
                 i < objectNodes.getLength();
                 i++) {

                Element objectElement =
                        (Element) objectNodes.item(i);


                /*
                 * ----------------------------------------------------
                 * v:imagedata
                 * ----------------------------------------------------
                 */
                Element imageData =
                        findDescendant(
                                objectElement,
                                VML_NS,
                                "imagedata"
                        );


                /*
                 * ----------------------------------------------------
                 * o:OLEObject
                 * ----------------------------------------------------
                 */
                Element oleObject =
                        findDescendant(
                                objectElement,
                                OFFICE_NS,
                                "OLEObject"
                        );


                /*
                 * Python:
                 *
                 * if imagedata_elem is None or ole_elem is None:
                 *     continue
                 */
                if (imageData == null ||
                    oleObject == null) {

                    continue;
                }


                /*
                 * ----------------------------------------------------
                 * preview r:id
                 * ----------------------------------------------------
                 */
                String previewRid =
                        imageData.getAttributeNS(
                                REL_NS,
                                "id"
                        );


                /*
                 * ----------------------------------------------------
                 * OLE r:id
                 * ----------------------------------------------------
                 */
                String oleRid =
                        oleObject.getAttributeNS(
                                REL_NS,
                                "id"
                        );


                if (previewRid == null ||
                    previewRid.isBlank() ||
                    oleRid == null ||
                    oleRid.isBlank()) {

                    continue;
                }


                /*
                 * ----------------------------------------------------
                 * rId → 실제 ZIP 경로
                 * ----------------------------------------------------
                 */
                String previewTarget =
                        ridToTarget.get(previewRid);

                String oleTarget =
                        ridToTarget.get(oleRid);


                if (previewTarget == null ||
                    oleTarget == null) {

                    log.warn(
                            "[EmbeddedService] OLE 관계를 찾을 수 없음 - previewRid={}, oleRid={}",
                            previewRid,
                            oleRid
                    );

                    continue;
                }


                /*
                 * Python과 동일하게
                 *
                 * media / embeddings 관계만 허용
                 */
                if (!previewTarget.startsWith("word/media/")
                        || !oleTarget.startsWith("word/embeddings/")) {

                    continue;
                }


                /*
                 * ----------------------------------------------------
                 * ZIP 내부 실제 데이터 추출
                 * ----------------------------------------------------
                 */
                ZipEntry oleEntry =
                        zipFile.getEntry(oleTarget);

                ZipEntry previewEntry =
                        zipFile.getEntry(previewTarget);


                if (oleEntry == null ||
                    previewEntry == null) {

                    log.warn(
                            "[EmbeddedService] ZIP Entry 없음 - ole={}, preview={}",
                            oleTarget,
                            previewTarget
                    );

                    continue;
                }


                /*
                 * ----------------------------------------------------
                 * 임시 파일 저장
                 * ----------------------------------------------------
                 */
                File oleFile =
                        saveZipEntry(
                                zipFile,
                                oleEntry,
                                "ole"
                        );

                File previewFile =
                        saveZipEntry(
                                zipFile,
                                previewEntry,
                                "preview"
                        );


                /*
                 * ----------------------------------------------------
                 * 주변 문단 텍스트
                 * ----------------------------------------------------
                 */
                String anchorText =
                        findAnchorText(
                                objectElement,
                                documentXml
                        );


                /*
                 * ----------------------------------------------------
                 * 실제 Embedded 파일 분석
                 *
                 * 기존 OleEmbeddedExtractor /
                 * OoxmlEmbeddedExtractor 사용
                 * ----------------------------------------------------
                 */
                EmbeddedObjectInfo extractedInfo =
                        extract(oleFile);


                if (extractedInfo == null) {
                    continue;
                }


                /*
                 * ----------------------------------------------------
                 * Python OleItem에 해당하는 정보 추가
                 * ----------------------------------------------------
                 */
                EmbeddedObjectInfo result =
                        EmbeddedObjectInfo.builder()

                                .fileName(
                                        extractedInfo.getFileName()
                                )

                                .fileExtension(
                                        extractedInfo.getFileExtension()
                                )

                                .objectType(
                                        extractedInfo.getObjectType()
                                )

                                .extractedPath(
                                        extractedInfo.getExtractedPath()
                                )

                                .fileHash(
                                        extractedInfo.getFileHash()
                                )

                                .orderHint(order)

                                .oleName(oleTarget)

                                .previewName(previewTarget)

                                .previewExtension(
                                        getExtension(previewTarget)
                                )

                                .previewPath(
                                        previewFile
                                                .getAbsolutePath()
                                )

                                .anchorText(anchorText)

                                .build();


                results.add(result);

                log.info(
                        "[EmbeddedService] DOCX OLE 발견 - order={}, ole={}, preview={}, anchor={}",
                        order,
                        oleTarget,
                        previewTarget,
                        anchorText
                );

                order++;
            }
        }

        log.info(
                "[EmbeddedService] DOCX Embedded 추출 완료 - count={}",
                results.size()
        );

        return results;
    }


    /**
     * 하나의 Embedded 파일을 실제 파일 형식에 따라 처리한다.
     */
    public EmbeddedObjectInfo extract(
            File embeddedFile) throws Exception {

        if (embeddedFile == null ||
            !embeddedFile.exists()) {

            throw new IllegalArgumentException(
                    "Embedded 파일이 존재하지 않습니다."
            );
        }

        org.apache.poi.poifs.filesystem.FileMagic magic =
                org.apache.poi.poifs.filesystem.FileMagic
                        .valueOf(embeddedFile);


        log.debug(
                "[EmbeddedService] Embedded 분석 - name={}, magic={}",
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
                    oleEmbeddedExtractor.extract(
                            embeddedFile
                    );
        };
    }


    /**
     * ============================================================
     * DOC
     * ============================================================
     *
     * 기존 DOC 처리 로직은 그대로 사용.
     */
    private List<EmbeddedObjectInfo> extractEmbeddedFromDoc(
            File file) throws Exception {

        List<EmbeddedObjectInfo> results =
                new ArrayList<>();

        try (
                org.apache.poi.poifs.filesystem.POIFSFileSystem fs =
                        new org.apache.poi.poifs.filesystem.POIFSFileSystem(file)
        ) {

            org.apache.poi.poifs.filesystem.DirectoryEntry root =
                    fs.getRoot();


            if (!root.hasEntry("ObjectPool")) {

                log.debug(
                        "[EmbeddedService] ObjectPool 없음: {}",
                        file.getName()
                );

                return results;
            }


            org.apache.poi.poifs.filesystem.Entry objectPoolEntry =
                    root.getEntry("ObjectPool");


            if (!(objectPoolEntry
                    instanceof org.apache.poi.poifs.filesystem.DirectoryEntry objectPool)) {

                return results;
            }


            for (
                    org.apache.poi.poifs.filesystem.Entry entry :
                    objectPool
            ) {

                if (!(entry
                        instanceof org.apache.poi.poifs.filesystem.DirectoryEntry storage)) {

                    continue;
                }


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
     * DOC OLE Storage 처리.
     */
    private EmbeddedObjectInfo extractFromStorage(
            org.apache.poi.poifs.filesystem.DirectoryEntry storage)
            throws Exception {

        org.apache.poi.poifs.filesystem.DocumentEntry ole10Native = null;
        org.apache.poi.poifs.filesystem.DocumentEntry packageEntry = null;
        org.apache.poi.poifs.filesystem.DocumentEntry contentsEntry = null;


        for (
                org.apache.poi.poifs.filesystem.Entry entry :
                storage
        ) {

            if (!(entry
                    instanceof org.apache.poi.poifs.filesystem.DocumentEntry documentEntry)) {

                continue;
            }


            String name =
                    documentEntry.getName();


            if ("\u0001Ole10Native".equalsIgnoreCase(name)) {

                ole10Native = documentEntry;

            } else if ("Package".equalsIgnoreCase(name)) {

                packageEntry = documentEntry;

            } else if ("CONTENTS".equalsIgnoreCase(name)) {

                contentsEntry = documentEntry;
            }
        }


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


        return null;
    }


    /**
     * ============================================================
     * XML / Relationship Utility
     * ============================================================
     */

    private Document parseXml(
            InputStream inputStream) throws Exception {

        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();

        /*
         * XXE 방지
         */
        factory.setNamespaceAware(true);

        try {
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true
            );
        } catch (Exception ignored) {
        }

        DocumentBuilder builder =
                factory.newDocumentBuilder();

        return builder.parse(inputStream);
    }


    /**
     * document.xml.rels
     *
     * rId1 → word/media/image1.emf
     * rId2 → word/embeddings/oleObject1.bin
     */
    private Map<String, String> buildRelationshipMap(
            Document relsXml) {

        Map<String, String> result =
                new HashMap<>();


        NodeList relationships =
                relsXml.getElementsByTagNameNS(
                        PACKAGE_REL_NS,
                        "Relationship"
                );


        for (int i = 0;
             i < relationships.getLength();
             i++) {

            Element relation =
                    (Element) relationships.item(i);


            String id =
                    relation.getAttribute("Id");

            String target =
                    relation.getAttribute("Target");


            if (id == null ||
                id.isBlank() ||
                target == null ||
                target.isBlank()) {

                continue;
            }


            /*
             * Python:
             *
             * target.replace("\\", "/")
             */
            target =
                    target.replace("\\", "/");


            /*
             * document.xml 기준 상대 경로.
             *
             * media/image1.emf
             * embeddings/oleObject1.bin
             *
             * → word/media/image1.emf
             * → word/embeddings/oleObject1.bin
             */
            if (target.startsWith("/")) {

                target =
                        target.substring(1);

            } else {

                target =
                        "word/" + target;
            }


            if (target.startsWith("word/media/")
                    || target.startsWith("word/embeddings/")) {

                result.put(id, target);
            }
        }


        return result;
    }


    /**
     * 특정 namespace/tag의 하위 Element 검색.
     */
    private Element findDescendant(
            Element parent,
            String namespace,
            String localName) {

        NodeList nodes =
                parent.getElementsByTagNameNS(
                        namespace,
                        localName
                );


        if (nodes.getLength() == 0) {

            return null;
        }


        return (Element) nodes.item(0);
    }


    /**
     * ZIP Entry를 임시 파일로 저장.
     */
    private File saveZipEntry(
            ZipFile zipFile,
            ZipEntry entry,
            String prefix)
            throws IOException {

        Path tempDir =
                Paths.get(
                        "temp",
                        "embedded"
                );


        Files.createDirectories(tempDir);


        String originalName =
                Paths.get(
                        entry.getName()
                ).getFileName().toString();


        File tempFile =
                tempDir.resolve(
                        UUID.randomUUID()
                                + "_"
                                + prefix
                                + "_"
                                + originalName
                ).toFile();


        try (
                InputStream is =
                        zipFile.getInputStream(entry);

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
     * document.xml의 OLE가 들어있는 문단 텍스트를 추출.
     *
     * Python:
     *
     * _build_ole_anchor_texts()
     */
    private String findAnchorText(
            Element objectElement,
            Document documentXml) {

        /*
         * OLE가 속한 w:p 찾기
         */
        Node current =
                objectElement;


        Element paragraph = null;


        while (current != null) {

            if (current instanceof Element element
                    && WORD_NS.equals(element.getNamespaceURI())
                    && "p".equals(element.getLocalName())) {

                paragraph = element;
                break;
            }


            current =
                    current.getParentNode();
        }


        if (paragraph == null) {
            return "";
        }


        /*
         * 현재 paragraph의 w:t 수집
         */
        String ownText =
                extractParagraphText(paragraph);


        /*
         * Python:
         *
         * if len(combined) < 8:
         *     next paragraph text 사용
         */
        if (ownText.length() >= 8) {

            return truncate(
                    normalizeText(ownText),
                    400
            );
        }


        /*
         * 다음 paragraph 탐색
         */
        Node currentParagraph =
                paragraph.getNextSibling();


        while (currentParagraph != null) {

            if (currentParagraph instanceof Element element
                    && WORD_NS.equals(element.getNamespaceURI())
                    && "p".equals(element.getLocalName())) {

                String nextText =
                        extractParagraphText(element);


                if (!nextText.isBlank()) {

                    String combined =
                            (ownText + " " + nextText)
                                    .trim();


                    return truncate(
                            normalizeText(combined),
                            400
                    );
                }
            }


            currentParagraph =
                    currentParagraph.getNextSibling();
        }


        return truncate(
                normalizeText(ownText),
                400
        );
    }


    /**
     * w:p 안의 모든 w:t 텍스트 결합.
     */
    private String extractParagraphText(
            Element paragraph) {

        NodeList textNodes =
                paragraph.getElementsByTagNameNS(
                        WORD_NS,
                        "t"
                );


        StringBuilder sb =
                new StringBuilder();


        for (int i = 0;
             i < textNodes.getLength();
             i++) {

            String text =
                    textNodes.item(i).getTextContent();


            if (text != null) {

                sb.append(text);
            }
        }


        return sb.toString();
    }


    private String normalizeText(
            String text) {

        if (text == null) {
            return "";
        }

        return text
                .replaceAll("\\s+", " ")
                .trim();
    }


    private String truncate(
            String text,
            int maxLength) {

        if (text == null) {
            return "";
        }

        if (text.length() <= maxLength) {
            return text;
        }

        return text.substring(0, maxLength);
    }


    private String getExtension(
            String fileName) {

        if (fileName == null ||
            fileName.isBlank()) {

            return "";
        }


        int index =
                fileName.lastIndexOf('.');


        if (index < 0 ||
            index == fileName.length() - 1) {

            return "";
        }


        return fileName
                .substring(index + 1)
                .toLowerCase();
    }


    /**
     * 기존 DOC stream 저장용.
     */
    private File saveDocumentEntry(
            org.apache.poi.poifs.filesystem.DocumentEntry documentEntry,
            String type)
            throws IOException {

        Path tempDir =
                Paths.get(
                        "temp",
                        "embedded"
                );


        Files.createDirectories(tempDir);


        File tempFile =
                tempDir.resolve(
                        UUID.randomUUID()
                                + "_"
                                + type
                                + ".bin"
                ).toFile();


        try (
                org.apache.poi.poifs.filesystem.DocumentInputStream dis =
                        new org.apache.poi.poifs.filesystem.DocumentInputStream(
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