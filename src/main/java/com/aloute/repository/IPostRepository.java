package com.aloute.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.aloute.model.document.Post;

public interface IPostRepository extends MongoRepository<Post, String> {

    /** Feed gồm bài gốc và quote repost có lời bình; bỏ repost thuần vì đã chuyển sang collection reposts. */
    @org.springframework.data.mongodb.repository.Query("{'moderationStatus': {$ne: 'HIDDEN'}, '$or': [ {'repostOfId': null}, {'repostOfId': {$ne: null}, 'content': {$nin: [null, '']}} ]}")
    List<Post> findAllForFeed(Pageable pageable);

    @org.springframework.data.mongodb.repository.Query("{'userId': {$in: ?0}, 'moderationStatus': {$ne: 'HIDDEN'}, '$or': [ {'repostOfId': null}, {'repostOfId': {$ne: null}, 'content': {$nin: [null, '']}} ]}")
    List<Post> findByUserIdInOrderByCreatedAtDesc(List<String> userIds, Pageable pageable);

    /** Feed chi lay bai goc; repost thuong duoc luu trong collection reposts. */
    @org.springframework.data.mongodb.repository.Query("{'repostOfId': null, 'moderationStatus': {$ne: 'HIDDEN'}}")
    List<Post> findAllOriginalsByOrderByCreatedAtDesc(Pageable pageable);

    @org.springframework.data.mongodb.repository.Query(value = "{'userId': ?0, 'moderationStatus': {$ne: 'HIDDEN'}}", sort = "{'createdAt': -1}")
    List<Post> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** Tổng số bài của 1 người — hiện trên trang cá nhân, rẻ hơn nhiều so với tải hết bài về đếm. */
    long countByUserId(String userId);

    /** Bài viết trong 1 hội nhóm, mới nhất trước. */
    @org.springframework.data.mongodb.repository.Query(value = "{'communityId': ?0, 'repostOfId': null, 'moderationStatus': {$ne: 'HIDDEN'}}", sort = "{'createdAt': -1}")
    List<Post> findByCommunityIdOrderByCreatedAtDesc(String communityId, Pageable pageable);

    long countByCommunityId(String communityId);

}