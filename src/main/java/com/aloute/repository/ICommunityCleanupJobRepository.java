package com.aloute.repository;

import com.aloute.model.entity.CommunityCleanupJob;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ICommunityCleanupJobRepository
        extends JpaRepository<CommunityCleanupJob, Long> {
    List<CommunityCleanupJob> findByNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            LocalDateTime now, Pageable pageable);
}
