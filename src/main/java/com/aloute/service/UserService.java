package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.common.util.CloudinaryUrlUtil;
import com.aloute.dto.request.UpdateProfileRequest;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.model.entity.Friend;
import com.aloute.model.entity.User;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final ContentAccessService access;

    private final IUserRepository userRepository;
    private final IFriendRepository friendRepository;
    private final FriendService friendService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FeedCacheInvalidator feedCacheInvalidator;
    private final UserSearchQuery userSearchQuery;
    private final CloseFriendService closeFriendService;

    @Autowired
    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access,
            FeedCacheInvalidator feedCacheInvalidator,
            UserSearchQuery userSearchQuery, CloseFriendService closeFriendService) {
        this.access = access;
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.friendService = friendService;
        this.messagingTemplate = messagingTemplate;
        this.feedCacheInvalidator = feedCacheInvalidator;
        this.userSearchQuery = userSearchQuery;
        this.closeFriendService = closeFriendService;
    }

    public UserService(IUserRepository users, IFriendRepository friends, FriendService friendService,
            SimpMessagingTemplate messaging, ContentAccessService access, FeedCacheInvalidator invalidator,
            UserSearchQuery search) {
        this(users, friends, friendService, messaging, access, invalidator, search, null);
    }

    /** Compatibility constructor for isolated service tests without cache/search collaborators. */
    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access) {
        this(userRepository, friendRepository, friendService, messagingTemplate, access, null, null);
    }

    /** Compatibility constructor for isolated service tests without the search collaborator. */
    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access,
            FeedCacheInvalidator feedCacheInvalidator) {
        this(userRepository, friendRepository, friendService, messagingTemplate, access,
                feedCacheInvalidator, null);
    }

    public Optional<User> findUserBasicInfo(Long userId) {
        return userRepository.findById(userId);
    }

    public List<User> findUsersByIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        return userRepository.findAllById(userIds);
    }

    public UserProfileDto getProfile(Long targetUserId, Long currentUserId) {
        User user =
                userRepository
                        .findById(targetUserId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        access.requireProfile(currentUserId, user);
        IFriendRepository.ProfileRelationshipStats relationshipStats =
                friendRepository.getProfileRelationshipStats(targetUserId, currentUserId);

        UserProfileDto dto = new UserProfileDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        dto.setCoverUrl(CloudinaryUrlUtil.cover(user.getCoverUrl()));
        dto.setBio(user.getBio());
        dto.setFollowerCount(relationshipStats == null ? 0L : relationshipStats.followerCount());
        dto.setFollowingCount(relationshipStats == null ? 0L : relationshipStats.followingCount());
        dto.setPrivateAccount(user.isPrivateAccount());

        if (currentUserId != null && !currentUserId.equals(targetUserId)) {
            boolean followedByCurrentUser =
                    relationshipStats != null && relationshipStats.viewerFollows();
            boolean mutualFriend = followedByCurrentUser
                    && relationshipStats.targetFollowsViewer();
            if ((relationshipStats == null || !relationshipStats.targetFollowsViewer())
                    && friendService.hasPendingFollowRequest(targetUserId, currentUserId)) {
                dto.setIncomingFollowRequestId(targetUserId + ":" + currentUserId);
            }
            dto.setFollowedByCurrentUser(followedByCurrentUser);
            dto.setMutualFriend(mutualFriend);
            if (closeFriendService != null && mutualFriend)
                dto.setCloseFriendStatus(closeFriendService.getStatusMap(currentUserId, List.of(targetUserId))
                        .getOrDefault(targetUserId, "NONE"));
            dto.setFollowRequestPending(
                    !followedByCurrentUser
                            && friendService.hasPendingFollowRequest(currentUserId, targetUserId));
        }

        dto.setCanMessage(canMessage(user, dto.isMutualFriend(), currentUserId, targetUserId));

        return dto;
    }

    public static boolean canMessage(
            User target, boolean mutualFriend, Long currentUserId, Long targetUserId) {
        if (currentUserId == null || currentUserId.equals(targetUserId)) return false;
        return mutualFriend || !target.isPrivateAccount();
    }

    public UserProfileDto updateProfile(Long userId, UpdateProfileRequest request) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        boolean privacyChanged = request.getPrivateAccount() != null
                && request.getPrivateAccount().booleanValue() != user.isPrivateAccount();
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        if (request.getPrivateAccount() != null) {
            user.setPrivateAccount(request.getPrivateAccount());
        }
        userRepository.save(user);
        if (feedCacheInvalidator != null) {
            if (privacyChanged) feedCacheInvalidator.invalidateAll();
            else feedCacheInvalidator.invalidateViewer(userId);
        }
        return getProfile(userId, userId);
    }

    public void updateAvatar(Long userId, String avatarUrl) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
        if (feedCacheInvalidator != null) feedCacheInvalidator.invalidateViewer(userId);
        Map<String, Object> payload = Map.of("userId", userId, "avatarUrl", avatarUrl);
        List<Long> friendIds = friendService.getMutualFriendIds(userId);
        for (User friend : userRepository.findAllById(friendIds)) {
            messagingTemplate.convertAndSendToUser(
                    friend.getUsername(), "/queue/avatar-updates", payload);
        }

        messagingTemplate.convertAndSendToUser(
                user.getUsername(), "/queue/avatar-updates", payload);
    }

    private static final int USER_SEARCH_LIMIT = 20;

    public List<UserProfileDto> searchUsers(String keyword, Long currentUserId) {
        return searchUsers(keyword, currentUserId, 0, USER_SEARCH_LIMIT);
    }

    public List<UserProfileDto> searchUsers(
            String keyword, Long currentUserId, int page, int size) {
        return searchUsersInternal(keyword, currentUserId, page, size, false);
    }

    /** Search-page results must not include the account that is performing the search. */
    public List<UserProfileDto> searchUsersExcludingCurrentUser(
            String keyword, Long currentUserId, int page, int size) {
        return searchUsersInternal(keyword, currentUserId, page, size, true);
    }

    private List<UserProfileDto> searchUsersInternal(
            String keyword, Long currentUserId, int page, int size, boolean excludeCurrentUser) {
        String term = keyword == null ? "" : keyword.trim();
        if (term.startsWith("@")) term = term.substring(1).trim();
        if (term.isBlank()) return Collections.emptyList();
        if (userSearchQuery == null) {
            throw new IllegalStateException("User search is not available without UserSearchQuery");
        }

        // Exclude the current account in the indexed query before pagination, so it cannot occupy
        // a result slot. Keep a defensive filter here as well for stale/mocked result sets.
        int safePage = Math.max(0, Math.min(10_000, page));
        int safeSize = Math.max(1, Math.min(50, size));
        List<Long> ids = excludeCurrentUser && currentUserId != null
                ? userSearchQuery.findIds(term, safePage, safeSize, currentUserId)
                : userSearchQuery.findIds(term, safePage, safeSize);
        if (ids.isEmpty()) return Collections.emptyList();
        Map<Long, User> byId = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) byId.put(user.getId(), user);
        List<User> users = ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(user -> !excludeCurrentUser || currentUserId == null
                        || !currentUserId.equals(user.getId()))
                .toList();
        return toProfiles(users, currentUserId);
    }

    public UserProfileDto getLiteProfile(Long userId) {
        return userRepository
                .findById(userId)
                .map(
                        user -> {
                            UserProfileDto dto = new UserProfileDto();
                            dto.setId(user.getId());
                            dto.setUsername(user.getUsername());
                            dto.setFullName(user.getFullName());
                            dto.setAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
                            return dto;
                        })
                .orElse(null);
    }

    public List<UserProfileDto> toProfiles(List<User> users, Long currentUserId) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }

        Set<Long> visibleIds = access.visibleUserIds(currentUserId, users);
        users = users.stream().filter(u -> visibleIds.contains(u.getId())).toList();
        if (users.isEmpty()) return List.of();
        List<Long> ids = users.stream().map(User::getId).collect(Collectors.toList());

        Map<Long, Long> followerCounts = new HashMap<>();
        for (Object[] row : friendRepository.countFollowersGrouped(ids)) {
            followerCounts.put((Long) row[0], (Long) row[1]);
        }

        Set<Long> followingSet = Collections.emptySet();
        Set<Long> followerSet = Collections.emptySet();
        Set<Long> pendingRequestTargetIds = Collections.emptySet();

        if (currentUserId != null) {

            followingSet =
                    friendRepository.findByFollowerIdAndFollowedIdIn(currentUserId, ids).stream()
                            .map(Friend::getFollowedId)
                            .collect(Collectors.toCollection(HashSet::new));
            followerSet =
                    friendRepository.findByFollowedIdAndFollowerIdIn(currentUserId, ids).stream()
                            .map(Friend::getFollowerId)
                            .collect(Collectors.toCollection(HashSet::new));
            if (friendService != null) {
                Set<Long> foundPending = friendService.pendingFollowRequestTargetIds(currentUserId, ids);
                if (foundPending != null) pendingRequestTargetIds = foundPending;
            }

        }

        List<UserProfileDto> result = new java.util.ArrayList<>(users.size());
        for (User user : users) {
            Long id = user.getId();

            UserProfileDto dto = new UserProfileDto();
            dto.setId(id);
            dto.setUsername(user.getUsername());
            dto.setFullName(user.getFullName());
            dto.setAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
            dto.setCoverUrl(CloudinaryUrlUtil.cover(user.getCoverUrl()));
            dto.setBio(user.getBio());
            dto.setFollowerCount(followerCounts.getOrDefault(id, 0L));
            dto.setPrivateAccount(user.isPrivateAccount());

            if (currentUserId != null) {
                boolean iFollow = followingSet.contains(id);
                dto.setFollowedByCurrentUser(iFollow);
                dto.setFollowRequestPending(!iFollow && pendingRequestTargetIds.contains(id));
                dto.setMutualFriend(iFollow && followerSet.contains(id));
                dto.setCanMessage(canMessage(user, dto.isMutualFriend(), currentUserId, id));
            }
            result.add(dto);
        }
        return result;
    }

    public List<UserProfileDto> getProfiles(List<Long> userIds, Long currentUserId) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, User> byId =
                userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        List<User> ordered =
                userIds.stream()
                        .map(byId::get)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());

        return toProfiles(ordered, currentUserId);
    }
}
