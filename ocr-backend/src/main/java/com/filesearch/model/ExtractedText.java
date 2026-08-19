package com.filesearch.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "extracted_text")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtractedText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id", nullable = false)
    private FileMetadata fileMetadata;

    @Column(name = "content", columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "extraction_type", length = 20)
    private String extractionType;

    @Column(name = "confidence", precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        if (extractionType == null) {
            extractionType = "TIKA";
        }
    }
}