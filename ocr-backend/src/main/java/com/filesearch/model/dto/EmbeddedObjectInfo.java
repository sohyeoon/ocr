package com.filesearch.model.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmbeddedObjectInfo {
    
	private String fileName;
	
	private String content;

    private String fileExtension;

    private String objectType;

    private String extractedPath;
    
    private Integer parentPageNumber;

    /**
     * 파일 SHA-256
     */
    private String fileHash;

    /**
     * DOCX 내부 document.xml에서의 OLE 등장 순서
     */
    private Integer orderHint;

    /**
     * OLE 실제 데이터
     *
     * 예:
     * word/embeddings/oleObject1.bin
     */
    private String oleName;

    /**
     * OLE 미리보기 이미지
     *
     * 예:
     * word/media/image1.emf
     */
    private String previewName;

    /**
     * 미리보기 이미지 확장자
     *
     * 예:
     * emf, wmf, png
     */
    private String previewExtension;

    /**
     * 미리보기 이미지가 저장된 실제 경로
     */
    private String previewPath;

    /**
     * OLE 개체 주변 텍스트
     */
    private String anchorText;
}
