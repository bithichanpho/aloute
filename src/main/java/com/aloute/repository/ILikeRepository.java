package com.aloute.repository;

import com.aloute.model.document.Like;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ILikeRepository extends MongoRepository<Like, String> {

    Optional<Like> findByPostIdAndUserId(String postId, String userId);

    boolean existsByPostIdAndUserId(String postId, String userId);

    long countByPostId(String postId);

    List<Like> findByPostIdInAndUserId(List<String> postIds, String userId);

    long deleteByPostId(String postId);
}
