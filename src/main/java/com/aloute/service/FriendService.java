package com.aloute.service;

import com.aloute.notifications.api.Notifications;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.entity.Friend;
import com.aloute.model.document.FollowRequest;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.stereotype.Service;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FriendService {

    private final ContentAccessService access;

    private final PostgresDocumentStore documentStore;
    private final IUserRepository users;
    private final IFriendRepository friendRepository;
    private final Notifications notificationService;
    private final FeedCacheInvalidator feedCacheInvalidator;
    private com.aloute.social.api.FollowRequestEvents requestEvents = (recipient, type, follower, target, status) -> {};

    @Autowired(required = false)
    void setFollowRequestEvents(com.aloute.social.api.FollowRequestEvents events) {
        this.requestEvents = events;
    }

    @Autowired
    public FriendService(
            IFriendRepository friendRepository,
            Notifications notificationService,
            ContentAccessService access,
            PostgresDocumentStore documentStore,
            IUserRepository users,
            FeedCacheInvalidator feedCacheInvalidator) {
        this.access = access;
        this.documentStore = documentStore;
        this.users = users;
        this.friendRepository = friendRepository;
        this.notificationService = notificationService;
        this.feedCacheInvalidator = feedCacheInvalidator;
    }

    /** Compatibility constructor for isolated service tests. */
    public FriendService(
            IFriendRepository friendRepository,
            Notifications notificationService,
            ContentAccessService access,
            PostgresDocumentStore documentStore,
            IUserRepository users) {
        this(friendRepository, notificationService, access, documentStore, users, null);
    }

    @Caching(evict = {
        @CacheEvict(cacheNames = "relationshipIds", key = "'following:' + #currentUserId"),
        @CacheEvict(cacheNames = "relationshipIds", key = "'followers:' + #targetUserId"),
        @CacheEvict(cacheNames = "suggestedUsers", key = "#currentUserId + ':' + T(com.aloute.service.DiscoveryService).FEED_SUGGESTION_LIMIT")
    })
    @org.springframework.transaction.annotation.Transactional
    public boolean toggleFollow(Long currentUserId, Long targetUserId) {
        access.requireProfile(currentUserId, targetUserId);
        if (!currentUserId.equals(targetUserId)
                && users.findById(targetUserId).orElseThrow().isPrivateAccount()
                && !friendRepository.existsByFollowerIdAndFollowedId(currentUserId, targetUserId)) {
            String id = currentUserId + ":" + targetUserId;
            var pending = documentStore.findById(id, FollowRequest.class);
            if (pending != null) documentStore.remove(pending);
            else {
                var request = new FollowRequest();
                request.setId(id);
                request.setFollowerId(currentUserId);
                request.setTargetId(targetUserId);
                documentStore.save(request);
                notificationService.notify(targetUserId, currentUserId, "FOLLOW_REQUEST",
                        id + "|" + request.getCreatedAt(), "đã gửi yêu cầu theo dõi bạn");
            }
            com.aloute.common.util.TransactionCommitHooks.afterCommit(() -> {
                String status = pending == null ? "PENDING" : "NONE";
                requestEvents.publish(targetUserId, pending == null ? "REQUEST_CREATED" : "REQUEST_CANCELLED",
                        currentUserId, targetUserId, status);
                requestEvents.publish(currentUserId, "STATE_CHANGED", currentUserId, targetUserId, status);
            });
            return false;
        }
        if (currentUserId.equals(targetUserId)) {
            throw new BadRequestException("Không thể tự follow chính mình");
        }

        boolean following = friendRepository
                .findByFollowerIdAndFollowedId(currentUserId, targetUserId)
                .map(existing -> {
                    friendRepository.delete(existing);
                    return false;
                })
                .orElseGet(() -> {
                    friendRepository.save(new Friend(currentUserId, targetUserId));
                    notificationService.notify(
                            targetUserId, currentUserId, "FOLLOW", null, "đã theo dõi bạn");
                    return true;
                });
        if (feedCacheInvalidator != null) feedCacheInvalidator.invalidateViewer(currentUserId);
        return following;
    }

    @Cacheable(cacheNames = "relationshipIds", key = "'following:' + #userId")
    public List<Long> getFollowingIds(Long userId) {
        return friendRepository.findByFollowerId(userId).stream()
                .map(Friend::getFollowedId)
                .collect(Collectors.toList());
    }

    public List<Long> getFollowingIds(Long userId, int page, int size) {
        return friendRepository.findByFollowerId(
                        userId, org.springframework.data.domain.PageRequest.of(page, size))
                .stream().map(Friend::getFollowedId).toList();
    }

    public String followStatus(Long currentUserId, Long targetUserId) {
        access.requireProfile(currentUserId, targetUserId);
        if (friendRepository.existsByFollowerIdAndFollowedId(currentUserId, targetUserId))
            return "FOLLOWING";
        return documentStore.exists(
                        com.aloute.persistence.postgres.document.Query.query(
                                com.aloute.persistence.postgres.document.Criteria.where("_id")
                                        .is(currentUserId + ":" + targetUserId)),
                        FollowRequest.class)
                ? "PENDING"
                : "NONE";
    }

    public boolean hasPendingFollowRequest(Long currentUserId, Long targetUserId) {
        if (currentUserId == null
                || targetUserId == null
                || currentUserId.equals(targetUserId)) {
            return false;
        }
        return documentStore.exists(
                com.aloute.persistence.postgres.document.Query.query(
                        com.aloute.persistence.postgres.document.Criteria.where("_id")
                                .is(currentUserId + ":" + targetUserId)),
                FollowRequest.class);
    }

    public java.util.Set<Long> pendingFollowRequestTargetIds(Long followerId, List<Long> targetIds) {
        if (followerId == null || targetIds == null || targetIds.isEmpty()) return java.util.Set.of();
        return documentStore.find(com.aloute.persistence.postgres.document.Query.query(
                new com.aloute.persistence.postgres.document.Criteria().andOperator(
                        com.aloute.persistence.postgres.document.Criteria.where("followerId").is(followerId),
                        com.aloute.persistence.postgres.document.Criteria.where("targetId").in(targetIds)))
                .limit(targetIds.size()), com.aloute.model.document.FollowRequest.class).stream()
                .map(com.aloute.model.document.FollowRequest::getTargetId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Cacheable(cacheNames = "relationshipIds", key = "'followers:' + #userId")
    public List<Long> getFollowerIds(Long userId) {
        return friendRepository.findByFollowedId(userId).stream()
                .map(Friend::getFollowerId)
                .collect(Collectors.toList());
    }

    public List<Long> getFollowerIds(Long userId, int page, int size) {
        return friendRepository.findByFollowedId(
                        userId, org.springframework.data.domain.PageRequest.of(page, size,
                                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("id"))))
                .stream().map(Friend::getFollowerId).toList();
    }

    @Caching(evict = {
        @CacheEvict(cacheNames = "relationshipIds", key = "'following:' + #followerId"),
        @CacheEvict(cacheNames = "relationshipIds", key = "'followers:' + #currentUserId"),
        @CacheEvict(cacheNames = "suggestedUsers", key = "#followerId + ':' + T(com.aloute.service.DiscoveryService).FEED_SUGGESTION_LIMIT"),
        @CacheEvict(cacheNames = "suggestedUsers", key = "#currentUserId + ':' + T(com.aloute.service.DiscoveryService).FEED_SUGGESTION_LIMIT")
    })
    @org.springframework.transaction.annotation.Transactional
    public com.aloute.dto.response.FollowerRemovalResponse removeFollower(Long currentUserId, Long followerId) {
        if (currentUserId == null) throw new com.aloute.common.exception.UnauthorizedException("Cần đăng nhập");
        if (followerId == null || followerId <= 0 || currentUserId.equals(followerId))
            throw new BadRequestException("Người theo dõi không hợp lệ");
        access.requireProfile(currentUserId, currentUserId);
        int removed = friendRepository.removeFollower(currentUserId, followerId);
        // Remove a stale approved request in the same PostgreSQL transaction so it cannot be reused.
        documentStore.remove(com.aloute.persistence.postgres.document.Query.query(
                com.aloute.persistence.postgres.document.Criteria.where("_id").is(followerId + ":" + currentUserId)),
                FollowRequest.class);
        com.aloute.common.util.TransactionCommitHooks.afterCommit(() -> {
            if (feedCacheInvalidator != null) {
                feedCacheInvalidator.invalidateViewer(followerId);
                feedCacheInvalidator.invalidateViewer(currentUserId);
            }
        });
        return new com.aloute.dto.response.FollowerRemovalResponse(removed > 0,
                friendRepository.countByFollowedId(currentUserId));
    }

    public boolean isFriend(Long userIdA, Long userIdB) {
        if (userIdA == null || userIdB == null || userIdA.equals(userIdB)) {
            return false;
        }
        return !access.blocked(userIdA, userIdB)
                && friendRepository.existsByFollowerIdAndFollowedId(userIdA, userIdB)
                && friendRepository.existsByFollowerIdAndFollowedId(userIdB, userIdA);
    }

    public List<Long> getMutualFriendIds(Long userId) {
        return friendRepository.findMutualFollowedIds(userId);
    }
}
