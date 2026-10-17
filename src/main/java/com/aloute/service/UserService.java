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
    private final CloseFriendService closeFriendService;
    private final SimpMessagingTemplate messagingTemplate;
    private final FeedCacheInvalidator feedCacheInvalidator;
    private final UserSearchQuery userSearchQuery;

    @Autowired
    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            CloseFriendService closeFriendService,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access,
            FeedCacheInvalidator feedCacheInvalidator,
            UserSearchQuery userSearchQuery) {
        this.access = access;
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.friendService = friendService;
        this.closeFriendService = closeFriendService;
        this.messagingTemplate = messagingTemplate;
        this.feedCacheInvalidator = feedCacheInvalidator;
        this.userSearchQuery = userSearchQuery;
    }

    /** Compatibility constructor for isolated service tests (no feed cache, no user search). */
    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            CloseFriendService closeFriendService,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access,
            FeedCacheInvalidator feedCacheInvalidator) {
        this(userRepository, friendRepository, friendService, closeFriendService, messagingTemplate,
                access, feedCacheInvalidator, null);
    }

    /** Compatibility constructor for isolated service tests. */
    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            CloseFriendService closeFriendService,
            SimpMessagingTemplate messagingTemplate,
            ContentAccessService access) {
        this(userRepository, friendRepository, friendService, closeFriendService, messagingTemplate, access, null, null);
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
        access.requireProfile(currentUserId, targetUserId);
        User user =
                userRepository
                        .findById(targetUserId)
                        .orElseThrow(
                                () -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        UserProfileDto dto = new UserProfileDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        dto.setCoverUrl(CloudinaryUrlUtil.cover(user.getCoverUrl()));
        dto.setBio(user.getBio());
        dto.setFollowerCount(friendRepository.countByFollowedId(targetUserId));
        dto.setFollowingCount(friendRepository.countByFollowerId(targetUserId));
        dto.setCloseFriendCount(closeFriendService.getCloseFriendIds(targetUserId).size());
        dto.setPrivateAccount(user.isPrivateAccount());

        if (currentUserId != null) {
            dto.setFollowedByCurrentUser(
                    friendRepository.existsByFollowerIdAndFollowedId(currentUserId, targetUserId));
            dto.setMutualFriend(friendService.isFriend(currentUserId, targetUserId));
            dto.setFollowRequestPending(
                    "PENDING".equals(friendService.followStatus(currentUserId, targetUserId)));
            dto.setCloseFriendStatus(closeFriendService.getStatus(currentUserId, targetUserId));
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
        for (Long friendId : friendService.getMutualFriendIds(userId)) {
            userRepository
                    .findById(friendId)
                    .ifPresent(
                            friend ->
                                    messagingTemplate.convertAndSendToUser(
                                            friend.getUsername(),
                                            "/queue/avatar-updates",
                                            payload));
        }

        messagingTemplate.convertAndSendToUser(
                user.getUsername(), "/queue/avatar-updates", payload);
    }

    private static final int USER_SEARCH_LIMIT = 20;

    public List<UserProfileDto> searchUsers(String keyword, Long currentUserId) {
        String term = keyword == null ? "" : keyword.trim();
        if (term.startsWith("@")) term = term.substring(1).trim();
        if (term.isBlank()) return Collections.emptyList();
        if (userSearchQuery == null) {
            throw new IllegalStateException("User search is not available without UserSearchQuery");
        }

        // Ids come from the trigram-indexed query (newest first); entities are then loaded in one
        // batch (with their role) and put back in that order.
        List<Long> ids = userSearchQuery.findIds(term, USER_SEARCH_LIMIT);
        if (ids.isEmpty()) return Collections.emptyList();
        Map<Long, User> byId = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) byId.put(user.getId(), user);
        List<User> users = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
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
        Map<Long, String> closeStatuses = Collections.emptyMap();

        if (currentUserId != null) {

            followingSet =
                    friendRepository.findByFollowerId(currentUserId).stream()
                            .map(Friend::getFollowedId)
                            .collect(Collectors.toCollection(HashSet::new));
            followerSet =
                    friendRepository.findByFollowedId(currentUserId).stream()
                            .map(Friend::getFollowerId)
                            .collect(Collectors.toCollection(HashSet::new));

            closeStatuses = closeFriendService.getStatusMap(currentUserId);
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
                dto.setMutualFriend(iFollow && followerSet.contains(id));
                dto.setCloseFriendStatus(closeStatuses.getOrDefault(id, "NONE"));
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
