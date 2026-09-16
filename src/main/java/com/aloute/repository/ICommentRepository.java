package com.aloute.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Comment;

public interface ICommentRepository extends MongoRepository<Comment, String> {

    List<Comment> findByPostIdOrderByCreatedAtAsc(String postId, org.springframework.data.domain.Pageable pageable);

    /** Backward-compatible helper for callers that really need all comments. */
    default List<Comment> findByPostIdOrderByCreatedAtAsc(String postId) {
        return findByPostIdOrderByCreatedAtAsc(postId, org.springframework.data.domain.PageRequest.of(0, 50));
    }

    long countByPostId(String postId);
    
    long countByParentCommentId(String parentCommentId);

    /** Dọn bình luận khi bài viết bị xóa. */
    long deleteByPostId(String postId);
}
