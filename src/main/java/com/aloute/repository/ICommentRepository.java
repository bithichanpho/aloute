package com.aloute.repository;

import com.aloute.model.document.Comment;
import com.aloute.repository.document.DocumentCrudRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import java.util.List;

public interface ICommentRepository extends DocumentCrudRepository<Comment, String> {
    List<Comment> findByPostIdOrderByCreatedAtAsc(String postId, Pageable pageable);
    List<Comment> findByPostIdAndParentCommentIdIsNullOrderByCreatedAtAsc(String postId, Pageable pageable);
    default List<Comment> findByPostIdOrderByCreatedAtAsc(String postId) {
        return findByPostIdOrderByCreatedAtAsc(postId, PageRequest.of(0, 50));
    }
    long countByPostId(String postId);
    long countByParentCommentId(String parentCommentId);
    long deleteByPostId(String postId);
}
