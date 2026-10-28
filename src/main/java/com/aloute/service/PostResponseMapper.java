package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.CloudinaryUrlUtil;
import com.aloute.common.util.TimeAgoUtil;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Like;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.model.entity.User;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts posts to response DTOs without mixing response assembly into post mutation use cases. */
@Service
public class PostResponseMapper {
    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final ILikeRepository likeRepository;
    private final IRepostRepository repostRepository;
    private final ContentAccessService access;
    private final RepostPresentationService repostPresentationService;

    public PostResponseMapper(
            IPostRepository postRepository,
            IUserRepository userRepository,
            ILikeRepository likeRepository,
            IRepostRepository repostRepository,
            ContentAccessService access,
            RepostPresentationService repostPresentationService) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.likeRepository = likeRepository;
        this.repostRepository = repostRepository;
        this.access = access;
        this.repostPresentationService = repostPresentationService;
    }

    public PostResponse toResponse(Post post, Long currentUserId) {
        PostResponse dto = new PostResponse();
        dto.setId(post.getId());
        dto.setVisibility(post.getVisibility());
        dto.setContent(post.getContent());
        dto.setModerationStatus(post.getModerationStatus());
        dto.setCommunityPostStatus(post.getCommunityPostStatus());
        dto.setCreatedAt(post.getCreatedAt());
        dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

        Long actorId = Long.valueOf(post.getUserId());
        dto.setUserId(actorId);
        userRepository
                .findById(actorId)
                .ifPresent(
                        user -> {
                            dto.setUsername(user.getUsername());
                            dto.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
                        });

        String interactionPostId =
                post.getRepostOfId() != null ? post.getRepostOfId() : post.getId();
        dto.setInteractionPostId(interactionPostId);

        Post interactionPost = post;
        if (post.getRepostOfId() != null) {
            interactionPost = postRepository.findById(post.getRepostOfId()).orElse(null);
        }

        if (interactionPost != null && isPubliclyModerated(interactionPost)) {
            dto.setLikeCount(interactionPost.getLikeCount());
            dto.setCommentCount(interactionPost.getCommentCount());
            dto.setReactionCounts(
                    interactionPost.getReactionCounts() != null
                            ? interactionPost.getReactionCounts()
                            : new HashMap<>());
            dto.setRepostCount(interactionPost.getRepostCount());
            dto.setImageUrl(
                    post.getRepostOfId() != null
                                    && (post.getContent() == null || post.getContent().isBlank())
                            ? CloudinaryUrlUtil.feedImage(
                                    interactionPost.getImageUrl())
                            : CloudinaryUrlUtil.feedImage(
                                    post.getImageUrl()));
            dto.setVideoUrl(
                    post.getRepostOfId() != null
                                    && (post.getContent() == null || post.getContent().isBlank())
                            ? interactionPost.getVideoUrl()
                            : post.getVideoUrl());
        } else {
            dto.setLikeCount(post.getLikeCount());
            dto.setCommentCount(post.getCommentCount());
            dto.setReactionCounts(
                    post.getReactionCounts() != null ? post.getReactionCounts() : new HashMap<>());
            dto.setRepostCount(post.getRepostCount());
            dto.setImageUrl(CloudinaryUrlUtil.feedImage(post.getImageUrl()));
            dto.setVideoUrl(post.getVideoUrl());
        }

        if (currentUserId != null) {
            String userIdStr = String.valueOf(currentUserId);
            Like current =
                    likeRepository.findByPostIdAndUserId(interactionPostId, userIdStr).orElse(null);
            dto.setLikedByCurrentUser(current != null);
            if (current != null) {
                dto.setCurrentUserReaction(current.getReactionType());
            }
            dto.setRepostedByCurrentUser(
                    repostRepository
                            .findByUserIdAndPostId(userIdStr, interactionPostId)
                            .isPresent());
        }

        if (post.getRepostOfId() != null) {
            dto.setRepostOf(buildRepostOrigin(post.getRepostOfId()));
        }
        repostPresentationService.attachRecentReposters(currentUserId, java.util.List.of(dto));

        return dto;
    }

    private boolean isPubliclyModerated(Post post) {
        ModerationStatus status = post == null ? null : post.getModerationStatus();
        // Treat legacy records without a status as approved. HIDDEN and PENDING remain blocked.
        // FLAGGED is a moderation signal, not an automatic publishing gate, when the engine
        // successfully evaluated the content. A FLAGGED result with a failure reason stays fail-closed.
        com.aloute.model.document.CommunityPostStatus communityStatus =
                post == null ? null : post.getCommunityPostStatus();
        boolean moderationReadable = status == null || status == ModerationStatus.APPROVED
                || (status == ModerationStatus.FLAGGED
                        && (post.getModerationFailureReason() == null || post.getModerationFailureReason().isBlank()));
        return moderationReadable
                && (communityStatus == null
                        || communityStatus == com.aloute.model.document.CommunityPostStatus.APPROVED);
    }

    private PostResponse.RepostOrigin buildRepostOrigin(String originalPostId) {
        PostResponse.RepostOrigin origin = new PostResponse.RepostOrigin();
        origin.setPostId(originalPostId);

        Post original = postRepository.findById(originalPostId).orElse(null);
        if (original == null || !isPubliclyModerated(original)) {
            origin.setDeleted(true);
            return origin;
        }

        origin.setContent(original.getContent());
        origin.setVisibility(original.getVisibility());
        origin.setImageUrl(
                CloudinaryUrlUtil.feedImage(original.getImageUrl()));
        origin.setVideoUrl(original.getVideoUrl());
        origin.setTimeAgo(TimeAgoUtil.format(original.getCreatedAt()));

        origin.setRepostCount(original.getRepostCount());

        Long originalUserId = Long.valueOf(original.getUserId());
        origin.setUserId(originalUserId);
        userRepository
                .findById(originalUserId)
                .ifPresent(
                        user -> {
                            origin.setUsername(user.getUsername());
                            origin.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
                        });

        return origin;
    }

    public List<PostResponse> toResponses(List<Post> posts, Long currentUserId) {
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }

        ContentAccessService.AccessBatch permissions = access.forPosts(posts, currentUserId);
        return toReadableResponses(permissions.readable(posts), currentUserId, permissions);
    }

    public List<PostResponse> toReadableResponses(
            List<Post> posts, Long currentUserId, ContentAccessService.AccessBatch permissions) {
        if (posts.isEmpty()) return List.of();
        Map<Long, User> usersById = permissions.users();
        Map<String, Post> originalsById = permissions.originals();

        List<String> interactionPostIds =
                posts.stream()
                        .map(p -> p.getRepostOfId() != null ? p.getRepostOfId() : p.getId())
                        .distinct()
                        .collect(Collectors.toList());

        Map<String, String> reactionByPostId = new HashMap<>();
        Set<String> repostedByCurrentUserIds = Collections.emptySet();

        if (currentUserId != null) {
            String userIdStr = String.valueOf(currentUserId);

            for (Like like :
                    likeRepository.findByPostIdInAndUserId(interactionPostIds, userIdStr)) {
                reactionByPostId.put(like.getPostId(), like.getReactionType());
            }

            repostedByCurrentUserIds =
                    repostRepository.findByUserIdAndPostIdIn(userIdStr, interactionPostIds).stream()
                            .map(Repost::getPostId)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet());
        }

        final Map<String, String> finalReactionByPostId = reactionByPostId;
        final Set<String> finalRepostedIds = repostedByCurrentUserIds;

        return posts.stream()
                .map(
                        post -> {
                            PostResponse dto = new PostResponse();

                            dto.setId(post.getId());
                            dto.setVisibility(post.getVisibility());
                            dto.setContent(post.getContent());
                            dto.setModerationStatus(post.getModerationStatus());
                            dto.setCommunityPostStatus(post.getCommunityPostStatus());
                            dto.setCreatedAt(post.getCreatedAt());
                            dto.setTimeAgo(TimeAgoUtil.format(post.getCreatedAt()));

                            Long actorId = Long.valueOf(post.getUserId());
                            dto.setUserId(actorId);
                            User actor = usersById.get(actorId);
                            if (actor != null) {
                                dto.setUsername(actor.getUsername());
                                dto.setUserAvatarUrl(AvatarUtil.resolve(actor.getAvatarUrl()));
                            }

                            String interactionPostId =
                                    post.getRepostOfId() != null
                                            ? post.getRepostOfId()
                                            : post.getId();
                            dto.setInteractionPostId(interactionPostId);

                            Post interactionPost =
                                    post.getRepostOfId() != null
                                            ? originalsById.get(post.getRepostOfId())
                                            : post;

                            if (interactionPost != null && isPubliclyModerated(interactionPost)) {
                                dto.setLikeCount(interactionPost.getLikeCount());
                                dto.setCommentCount(interactionPost.getCommentCount());
                                dto.setReactionCounts(
                                        interactionPost.getReactionCounts() != null
                                                ? interactionPost.getReactionCounts()
                                                : new HashMap<>());
                                dto.setRepostCount(interactionPost.getRepostCount());

                                if (post.getRepostOfId() != null
                                        && (post.getContent() == null
                                                || post.getContent().isBlank())) {
                                    dto.setImageUrl(
                                            CloudinaryUrlUtil.feedImage(
                                                    interactionPost.getImageUrl()));
                                    dto.setVideoUrl(interactionPost.getVideoUrl());
                                } else {
                                    dto.setImageUrl(
                                            CloudinaryUrlUtil.feedImage(
                                                    post.getImageUrl()));
                                    dto.setVideoUrl(post.getVideoUrl());
                                }
                            } else {
                                dto.setLikeCount(post.getLikeCount());
                                dto.setCommentCount(post.getCommentCount());
                                dto.setReactionCounts(
                                        post.getReactionCounts() != null
                                                ? post.getReactionCounts()
                                                : new HashMap<>());
                                dto.setRepostCount(post.getRepostCount());
                                dto.setImageUrl(
                                        CloudinaryUrlUtil.feedImage(
                                                post.getImageUrl()));
                                dto.setVideoUrl(post.getVideoUrl());
                            }

                            String currentReaction = finalReactionByPostId.get(interactionPostId);
                            dto.setCurrentUserReaction(currentReaction);
                            dto.setLikedByCurrentUser(currentReaction != null);
                            dto.setRepostedByCurrentUser(
                                    finalRepostedIds.contains(interactionPostId));

                            if (post.getRepostOfId() != null) {
                                Post original = originalsById.get(post.getRepostOfId());
                                PostResponse.RepostOrigin origin = new PostResponse.RepostOrigin();
                                origin.setPostId(post.getRepostOfId());
                                if (original == null || !isPubliclyModerated(original)) {
                                    origin.setDeleted(true);
                                } else {
                                    origin.setContent(original.getContent());
                                    origin.setVisibility(original.getVisibility());
                                    origin.setImageUrl(
                                            CloudinaryUrlUtil.feedImage(
                                                    original.getImageUrl()));
                                    origin.setVideoUrl(original.getVideoUrl());
                                    origin.setTimeAgo(TimeAgoUtil.format(original.getCreatedAt()));
                                    origin.setRepostCount(original.getRepostCount());
                                    Long originalAuthorId = Long.valueOf(original.getUserId());
                                    origin.setUserId(originalAuthorId);
                                    User originalAuthor = usersById.get(originalAuthorId);
                                    if (originalAuthor != null) {
                                        origin.setUsername(originalAuthor.getUsername());
                                        origin.setUserAvatarUrl(
                                                AvatarUtil.resolve(originalAuthor.getAvatarUrl()));
                                    }
                                }
                                dto.setRepostOf(origin);
                            }

                            return dto;
                        })
                .collect(Collectors.toList());
    }


}
