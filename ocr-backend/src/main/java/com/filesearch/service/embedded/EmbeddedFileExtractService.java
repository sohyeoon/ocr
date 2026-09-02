package com.filesearch.service.embedded;

import java.io.File;

import org.apache.poi.poifs.filesystem.FileMagic;
import org.springframework.stereotype.Service;

import com.filesearch.model.dto.EmbeddedObjectInfo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


/*
 * 해당 파일 안에 들어있는 개체 파일을 찾아 밖으로 꺼내주는 역할
 * */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddedFileExtractService {
    
    private final OleEmbeddedExtractor oleEmbeddedExtractor;
    private final OoxmlEmbeddedExtractor ooxmlEmbeddedExtractor;

    public EmbeddedObjectInfo extract(File embeddedFile) throws Exception {

        FileMagic fileMagic = FileMagic.valueOf(embeddedFile);

        return switch (fileMagic) {

            case OLE2 ->
                    oleEmbeddedExtractor.extract(embeddedFile);

            case OOXML ->
                    ooxmlEmbeddedExtractor.extract(embeddedFile);

            default ->
                    throw new IllegalArgumentException(
                            "지원하지 않는 Embedded 파일 형식: "
                                    + embeddedFile.getName()
                    );
        };
    }
    
}