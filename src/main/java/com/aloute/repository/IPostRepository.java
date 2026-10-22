package com.aloute.repository;

import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.document.Post;
import com.aloute.repository.document.DocumentCrudRepository;
import org.springframework.data.domain.Pageable;
import java.util.List;

public interface IPostRepository extends DocumentCrudRepository<Post, String> {
    List<Post> findAllOriginalsByOrderByCreatedAtDesc(Pageable pageable);
    List<Post> findByCommunityIdOrderByCreatedAtDesc(String communityId, Pageable pageable);
    List<Post> findCommunityPostsByStatus(String communityId, CommunityPostStatus status, Pageable pageable);
}
