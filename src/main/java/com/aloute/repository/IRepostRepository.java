package com.aloute.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Repost;

public interface IRepostRepository extends MongoRepository<Repost, String> {

    Optional<Repost> findByUserIdAndPostId(String userId, String postId);

    List<Repost> findByUserIdAndPostIdIn(String userId, List<String> postIds);

    List<Repost> findByPostIdIn(List<String> postIds);

    List<Repost> findByUserIdIn(List<String> userIds, Pageable pageable);

    List<Repost> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<Repost> findByPostIdOrderByCreatedAtDesc(String postId, Pageable pageable);

    List<Repost> findByPostIdOrderByCreatedAtDesc(String postId);

    long countByPostId(String postId);

    long deleteByPostId(String postId);
}
