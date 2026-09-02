package com.filesearch.service.embedded;

import java.io.File;

import org.springframework.stereotype.Component;

import com.filesearch.model.dto.EmbeddedObjectInfo;

@Component
public class OoxmlEmbeddedExtractor {

    public EmbeddedObjectInfo extract(
            File embeddedFile) {

        String fileName =
                embeddedFile.getName();

        String extension =
                getExtension(fileName);

        return EmbeddedObjectInfo.builder()
                .fileName(fileName)
                .fileExtension(extension)
                .objectType("OOXML")
                .extractedPath(
                        embeddedFile.getAbsolutePath()
                )
                .build();
    }

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