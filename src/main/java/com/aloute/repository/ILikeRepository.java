package com.aloute.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Like;

public interface ILikeRepository extends MongoRepository<Like, String> {

    Optional<Like> findByPostIdAndUserId(String postId, String userId);

    boolean existsByPostIdAndUserId(String postId, String userId);

    long countByPostId(String postId);

    // Lấy những bài mà một user đã like trong danh sách bài đang hiển thị (dùng cho batch load ở PostService)
    List<Like> findByPostIdInAndUserId(
            List<String> postIds,
            String userId
    );
}