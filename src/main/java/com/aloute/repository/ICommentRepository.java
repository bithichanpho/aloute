package com.aloute.repository;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Comment;

public interface ICommentRepository extends MongoRepository<Comment, String> {

    List<Comment> findByPostIdOrderByCreatedAtAsc(String postId);

    long countByPostId(String postId);
    
    long countByParentCommentId(String parentCommentId);
}