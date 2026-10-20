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
            value = "{'communityId': ?0, 'repostOfId': null, 'moderationStatus': {$ne: 'HIDDEN'}, '$or': [{'communityPostStatus': null}, {'communityPostStatus': 'APPROVED'}]}",
            sort = "{'createdAt': -1}")
    List<Post> findByCommunityIdOrderByCreatedAtDesc(String communityId, Pageable pageable);

    @org.springframework.data.mongodb.repository.Query(
            value = "{'communityId': ?0, 'repostOfId': null, 'communityPostStatus': ?1}",
            sort = "{'createdAt': -1}")
    List<Post> findCommunityPostsByStatus(
            String communityId,
            com.aloute.model.document.CommunityPostStatus status,
            Pageable pageable);
}
