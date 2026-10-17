package com.aloute.repository;

import com.aloute.model.document.Comment;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ICommentRepository extends MongoRepository<Comment, String> {

    List<Comment> findByPostIdOrderByCreatedAtAsc(
            String postId, org.springframework.data.domain.Pageable pageable);

    /** Top-level comments only; replies are loaded lazily through the cursor endpoint. */
    List<Comment> findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAsc(
            String postId, org.springframework.data.domain.Pageable pageable);

    default List<Comment> findByPostIdOrderByCreatedAtAsc(String postId) {
        return findByPostIdOrderByCreatedAtAsc(
                postId, org.springframework.data.domain.PageRequest.of(0, 50));
    }

    long countByPostId(String postId);

    long countByParentCommentId(String parentCommentId);

    long deleteByPostId(String postId);
}
