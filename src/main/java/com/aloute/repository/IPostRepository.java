package com.aloute.repository;

import com.aloute.model.document.Post;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IPostRepository extends MongoRepository<Post, String> {


    @org.springframework.data.mongodb.repository.Query(
            "{'repostOfId': null, 'moderationStatus': {$ne: 'HIDDEN'}}")
    List<Post> findAllOriginalsByOrderByCreatedAtDesc(Pageable pageable);


    @org.springframework.data.mongodb.repository.Query(
            value = "{'communityId': ?0, 'repostOfId': null, 'moderationStatus': {$ne: 'HIDDEN'}}",
            sort = "{'createdAt': -1}")
    List<Post> findByCommunityIdOrderByCreatedAtDesc(String communityId, Pageable pageable);
}
