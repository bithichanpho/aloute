package com.aloute.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Post;

public interface IPostRepository extends MongoRepository<Post, String> {

    List<Post> findByUserIdInOrderByCreatedAtDesc(List<String> userIds, Pageable pageable);

    List<Post> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<Post> findByUserIdOrderByCreatedAtDesc(String userId);
}
