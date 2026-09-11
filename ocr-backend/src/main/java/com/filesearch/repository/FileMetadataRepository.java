package com.filesearch.repository;

import com.filesearch.model.FileMetadata;
import com.filesearch.model.FileStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    Optional<FileMetadata> findByFilePath(String filePath);
    List<FileMetadata> findByStatus(FileStatus status);
    long countByStatus(FileStatus status);
    boolean existsByFilePath(String filePath);
    List<FileMetadata> findByParentId(Long valueOf);
    Optional<FileMetadata> findByFileHash(String fileHash);
}