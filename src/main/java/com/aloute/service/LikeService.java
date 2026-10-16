package com.aloute.service;

import com.aloute.common.util.ReactionType;
import com.aloute.dto.response.LikeResponse;
import com.aloute.dto.response.ReactionResponse;
import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
import com.aloute.repository.ILikeRepository;
import com.mongodb.client.result.DeleteResult;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class LikeService {

    private final ILikeRepository likeRepository;
    private final PostService postService;
    private final NotificationService notificationService;
    private final MongoTemplate mongoTemplate;

    public LikeService(
            ILikeRepository likeRepository,
            PostService postService,
            NotificationService notificationService,
            MongoTemplate mongoTemplate) {
        this.likeRepository = likeRepository;
        this.postService = postService;
        this.notificationService = notificationService;
        this.mongoTemplate = mongoTemplate;
    }

    public LikeResponse setLike(String postId, Long currentUserId, boolean desiredLiked) {
        ReactionResponse reaction =
                setReaction(postId, currentUserId, desiredLiked ? ReactionType.LIKE : null);
        return new LikeResponse(
                postId, reaction.getCurrentUserReaction() != null, reaction.getTotalCount());
    }

    public boolean toggleLike(String postId, Long currentUserId) {
        String userIdStr = String.valueOf(currentUserId);
        boolean current = likeRepository.existsByPostIdAndUserId(postId, userIdStr);
        return setLike(postId, currentUserId, !current).isLiked();
    }

    public ReactionResponse setReaction(String postId, Long currentUserId, String requestedType) {
        Post post = postService.requireReadablePost(postId, currentUserId);
        String userIdStr = String.valueOf(currentUserId);
        boolean wantsReaction = requestedType != null && !requestedType.isBlank();
        String normalizedType = wantsReaction ? ReactionType.normalize(requestedType) : null;

        Like existing = likeRepository.findByPostIdAndUserId(postId, userIdStr).orElse(null);

        if (!wantsReaction) {

            if (existing != null) {
                removeReaction(postId, existing);
            }
        } else if (existing == null) {

            try {
                likeRepository.save(new Like(postId, userIdStr, normalizedType));
                postService.adjustReactionCount(postId, normalizedType, 1);
                notifyReacted(post, currentUserId);
            } catch (DuplicateKeyException ignored) {

            }
        } else if (normalizedType.equals(existing.getReactionType())) {

            removeReaction(postId, existing);
        } else {

            String oldType = existing.getReactionType();
            existing.setReactionType(normalizedType);
            likeRepository.save(existing);
            postService.adjustReactionCount(postId, oldType, -1);
            postService.adjustReactionCount(postId, normalizedType, 1);
        }

        Post refreshed = postService.getPostOrThrow(postId);
        Like current = likeRepository.findByPostIdAndUserId(postId, userIdStr).orElse(null);
        Map<String, Integer> counts =
                refreshed.getReactionCounts() != null
                        ? refreshed.getReactionCounts()
                        : new HashMap<>();

        return new ReactionResponse(
                postId,
                current != null ? current.getReactionType() : null,
                Math.max(0, refreshed.getLikeCount()),
                counts);
    }

    private void removeReaction(String postId, Like existing) {
        Query query =
                Query.query(
                        Criteria.where("postId").is(postId).and("userId").is(existing.getUserId()));
        DeleteResult result = mongoTemplate.remove(query, Like.class);
        if (result.getDeletedCount() > 0) {
            postService.adjustReactionCount(postId, existing.getReactionType(), -1);
        }
    }

    private void notifyReacted(Post post, Long reactorId) {
        try {
            Long ownerId = Long.valueOf(post.getUserId());
            notificationService.notify(
                    ownerId, reactorId, "LIKE", post.getId(), "đã thích bài viết của bạn");
        } catch (org.springframework.core.task.TaskRejectedException ignored) {

        }
    }
}
