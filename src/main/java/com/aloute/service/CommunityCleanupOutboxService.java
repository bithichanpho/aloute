package com.aloute.service;

import com.aloute.model.entity.CommunityCleanupJob;
import com.aloute.repository.ICommunityCleanupJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records cleanup intent in the same PostgreSQL transaction that deletes the community. */
@Service
public class CommunityCleanupOutboxService {
    private final ICommunityCleanupJobRepository repository;

    public CommunityCleanupOutboxService(ICommunityCleanupJobRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void enqueue(Long communityId, String avatarUrl, String coverUrl) {
        if (communityId == null) {
            throw new IllegalArgumentException("communityId is required for community cleanup");
        }
        CommunityCleanupJob job = repository.findById(communityId).orElseGet(CommunityCleanupJob::new);
        job.setCommunityId(communityId);
        job.setAvatarUrl(avatarUrl);
        job.setCoverUrl(coverUrl);
        repository.save(job);
    }
}
