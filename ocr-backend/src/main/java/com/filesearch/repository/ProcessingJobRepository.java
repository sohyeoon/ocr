package com.filesearch.repository;

import com.filesearch.model.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, Long> {
    List<ProcessingJob> findByStatusIn(List<String> statuses);
    Optional<ProcessingJob> findTopByOrderByStartedAtDesc();
}