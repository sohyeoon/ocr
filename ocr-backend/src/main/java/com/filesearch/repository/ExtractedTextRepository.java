package com.filesearch.repository;

import com.filesearch.model.ExtractedText;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ExtractedTextRepository extends JpaRepository<ExtractedText, Long> {
    @Query(value = "SELECT * FROM extracted_text WHERE to_tsvector('simple', content) @@ plainto_tsquery('simple', :query)", nativeQuery = true)
    List<ExtractedText> fullTextSearch(@Param("query") String query);

    //void deleteByFileId(Long fileId);
}