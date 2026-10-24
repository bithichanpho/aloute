package com.aloute.repository;

import com.aloute.model.document.Like;
import com.aloute.repository.document.DocumentCrudRepository;
import java.util.List;
import java.util.Optional;

public interface ILikeRepository extends DocumentCrudRepository<Like, String> {
    Optional<Like> findByPostIdAndUserId(String postId, String userId);
    boolean existsByPostIdAndUserId(String postId, String userId);
    long countByPostId(String postId);
    List<Like> findByPostIdInAndUserId(List<String> postIds, String userId);
    long deleteByPostId(String postId);
}
