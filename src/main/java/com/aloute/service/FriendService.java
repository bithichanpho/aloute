package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.entity.Friend;
import com.aloute.model.document.FollowRequest;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class FriendService {

    private final ContentAccessService access;

    private final MongoTemplate mongo;
    private final IUserRepository users;
    private final IFriendRepository friendRepository;
    private final NotificationService notificationService;
    private final FeedCacheInvalidator feedCacheInvalidator;

    @Autowired
    public FriendService(
            IFriendRepository friendRepository,
            NotificationService notificationService,
            ContentAccessService access,
            MongoTemplate mongo,
            IUserRepository users,
            FeedCacheInvalidator feedCacheInvalidator) {
        this.access = access;
        this.mongo = mongo;
        this.users = users;
        this.friendRepository = friendRepository;
        this.notificationService = notificationService;
        this.feedCacheInvalidator = feedCacheInvalidator;
    }

    /** Compatibility constructor for isolated service tests. */
    public FriendService(
            IFriendRepository friendRepository,
            NotificationService notificationService,
            ContentAccessService access,
            MongoTemplate mongo,
            IUserRepository users) {
        this(friendRepository, notificationService, access, mongo, users, null);
    }

    @Caching(evict = {
        @CacheEvict(cacheNames = "relationshipIds", key = "'following:' + #currentUserId"),
        @CacheEvict(cacheNames = "relationshipIds", key = "'followers:' + #targetUserId")
    })
    @org.springframework.transaction.annotation.Transactional
    public boolean toggleFollow(Long currentUserId, Long targetUserId) {
        access.requireProfile(currentUserId, targetUserId);
        if (!currentUserId.equals(targetUserId)
                && users.findById(targetUserId).orElseThrow().isPrivateAccount()
                && !friendRepository.existsByFollowerIdAndFollowedId(currentUserId, targetUserId)) {
            String id = currentUserId + ":" + targetUserId;
            var pending = mongo.findById(id, FollowRequest.class);
            if (pending != null) mongo.remove(pending);
            else {
                var request = new FollowRequest();
                request.setId(id);
                request.setFollowerId(currentUserId);
                request.setTargetId(targetUserId);
                mongo.save(request);
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
        return mongo.exists(
                        org.springframework.data.mongodb.core.query.Query.query(
                                org.springframework.data.mongodb.core.query.Criteria.where("_id")
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
        return mongo.exists(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id")
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
