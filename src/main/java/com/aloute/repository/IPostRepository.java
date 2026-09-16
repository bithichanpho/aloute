package com.aloute.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Post;

public interface IPostRepository extends MongoRepository<Post, String> {

    @org.springframework.data.mongodb.repository.Query("{'userId': {$in: ?0}, 'moderationStatus': {$ne: 'HIDDEN'}}")
    List<Post> findByUserIdInOrderByCreatedAtDesc(List<String> userIds, Pageable pageable);

    @org.springframework.data.mongodb.repository.Query("{'moderationStatus': {$ne: 'HIDDEN'}}")
    List<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @org.springframework.data.mongodb.repository.Query(value = "{'userId': ?0, 'moderationStatus': {$ne: 'HIDDEN'}}", sort = "{'createdAt': -1}")
    List<Post> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** Tổng số bài của 1 người — hiện trên trang cá nhân, rẻ hơn nhiều so với tải hết bài về đếm. */
    long countByUserId(String userId);
}
