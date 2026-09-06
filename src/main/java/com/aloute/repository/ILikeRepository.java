package com.aloute.repository;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Like;

public interface ILikeRepository extends MongoRepository<Like, String> {

    Optional<Like> findByPostIdAndUserId(String postId, String userId);

    boolean existsByPostIdAndUserId(String postId, String userId);

    long countByPostId(String postId);
}