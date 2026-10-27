package com.aloute.service;

import com.aloute.notifications.api.Notifications;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.model.entity.Friend;
import com.aloute.model.document.FollowRequest;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.social.FollowRequestRealtimePublisher;

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
    private final FollowRequestRealtimePublisher followRequestRealtimePublisher;

    @Autowired
    public FriendService(
            IFriendRepository friendRepository,
            Notifications notificationService,
            ContentAccessService access,
            PostgresDocumentStore documentStore,
            IUserRepository users,
            FeedCacheInvalidator feedCacheInvalidator,
            FollowRequestRealtimePublisher followRequestRealtimePublisher) {
        this.access = access;
        this.documentStore = documentStore;
        this.users = users;
        this.friendRepository = friendRepository;
        this.notificationService = notificationService;
        this.feedCacheInvalidator = feedCacheInvalidator;
        this.followRequestRealtimePublisher = followRequestRealtimePublisher;
    }

    /** Compatibility constructor for isolated service tests. */
    public FriendService(
            IFriendRepository friendRepository,
            Notifications notificationService,
            ContentAccessService access,
            PostgresDocumentStore documentStore,
            IUserRepository users) {
        this(friendRepository, notificationService, access, documentStore, users, null, null);
    }

    /** Compatibility constructor retained for tests that provide a cache invalidator. */
    public FriendService(
            IFriendRepository friendRepository,
            Notifications notificationService,
            ContentAccessService access,
            PostgresDocumentStore documentStore,
            IUserRepository users,
            FeedCacheInvalidator feedCacheInvalidator) {
        this(friendRepository, notificationService, access, documentStore, users, feedCacheInvalidator, null);
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
            boolean cancelRequest = pending != null;
            if (cancelRequest) {
                documentStore.remove(pending);
            } else {
                var request = new FollowRequest();
                request.setId(id);
                request.setFollowerId(currentUserId);
                request.setTargetId(targetUserId);
                documentStore.save(request);
                // Follow requests are shown in the dedicated /follow-requests section, not Activity.
            }
            final String eventType = cancelRequest ? "REQUEST_CANCELLED" : "REQUEST_CREATED";
            final String nextStatus = cancelRequest ? "NONE" : "PENDING";
            if (followRequestRealtimePublisher != null) {
                TransactionCommitHooks.afterCommit(() -> {
                    followRequestRealtimePublisher.publish(
                            targetUserId, eventType, currentUserId, targetUserId, nextStatus);
                    // Synchronize other tabs belonging to the requester as well (search/profile state).
                    followRequestRealtimePublisher.publish(
                            currentUserId, "STATE_CHANGED", currentUserId, targetUserId, nextStatus);
                });
            }
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
                            targetUserId, currentUserId, "FOLLOW", String.valueOf(currentUserId), "đã theo dõi bạn");
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

    /** Batch query for pending requests sent by the current viewer, used by search and suggestions. */
    public java.util.Set<Long> pendingFollowRequestTargetIds(Long followerId, List<Long> targetIds) {
        if (followerId == null || targetIds == null || targetIds.isEmpty()) return java.util.Set.of();
        return documentStore.find(
                        com.aloute.persistence.postgres.document.Query.query(
                                new com.aloute.persistence.postgres.document.Criteria().andOperator(
                                        com.aloute.persistence.postgres.document.Criteria.where("followerId").is(followerId),
                                        com.aloute.persistence.postgres.document.Criteria.where("targetId").in(targetIds))),
                        FollowRequest.class)
                .stream()
                .map(FollowRequest::getTargetId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
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

    @Cacheable(cacheNames = "relationshipIds", key = "'followers:' + #userId")
    public List<Long> getFollowerIds(Long userId) {
        return friendRepository.findByFollowedId(userId).stream()
                .map(Friend::getFollowerId)
                .collect(Collectors.toList());
    }

    public List<Long> getFollowerIds(Long userId, int page, int size) {
        return friendRepository.findByFollowedId(
                        userId, org.springframework.data.domain.PageRequest.of(page, size))
                .stream().map(Friend::getFollowerId).toList();
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
