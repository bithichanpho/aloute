package com.aloute.service;

import com.aloute.common.util.AvatarUtil;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.dto.request.UpdateProfileRequest;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.model.entity.User;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.Map;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.aloute.model.entity.Friend;

@Service
public class UserService {

    private final IUserRepository userRepository;
    private final IFriendRepository friendRepository;
    private final FriendService friendService;
    private final CloseFriendService closeFriendService;
    private final SimpMessagingTemplate messagingTemplate;

    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            CloseFriendService closeFriendService,
            SimpMessagingTemplate messagingTemplate) {
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.friendService = friendService;
        this.closeFriendService = closeFriendService;
        this.messagingTemplate = messagingTemplate;
    }

    public Optional<User> findUserBasicInfo(Long userId) {
        return userRepository.findById(userId);
    }

    public UserProfileDto getProfile(Long targetUserId, Long currentUserId) {
        User user = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        UserProfileDto dto = new UserProfileDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        dto.setCoverUrl(user.getCoverUrl());
        dto.setBio(user.getBio());
        dto.setFollowerCount(friendRepository.countByFollowedId(targetUserId));
        dto.setFollowingCount(friendRepository.countByFollowerId(targetUserId));
        dto.setCloseFriendCount(closeFriendService.getCloseFriendIds(targetUserId).size());

        if (currentUserId != null) {
            dto.setFollowedByCurrentUser(
                    friendRepository.existsByFollowerIdAndFollowedId(currentUserId, targetUserId));
            dto.setMutualFriend(friendService.isFriend(currentUserId, targetUserId));
            dto.setCloseFriendStatus(closeFriendService.getStatus(currentUserId, targetUserId));
        }

        return dto;
    }

    public UserProfileDto updateProfile(Long userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName());
        }
        if (request.getBio() != null) {
            user.setBio(request.getBio());
        }
        userRepository.save(user);
        return getProfile(userId, userId);
    }

    public void updateAvatar(Long userId, String avatarUrl) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        user.setAvatarUrl(avatarUrl);
        userRepository.save(user);
        Map<String, Object> payload = Map.of("userId", userId, "avatarUrl", avatarUrl);
        for (Long friendId : friendService.getMutualFriendIds(userId)) {
            userRepository.findById(friendId).ifPresent(friend ->
                    messagingTemplate.convertAndSendToUser(friend.getUsername(), "/queue/avatar-updates", payload));
        }
        // đồng bộ các tab/thiết bị khác của chính người dùng
        messagingTemplate.convertAndSendToUser(user.getUsername(), "/queue/avatar-updates", payload);
    }

    public List<UserProfileDto> searchUsers(String keyword, Long currentUserId) {
        List<User> users = userRepository
                .findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCase(keyword, keyword);
        return toProfiles(users, currentUserId);
    }

    /**
     * Hồ sơ rút gọn cho thanh điều hướng: chỉ id/username/họ tên/avatar, đúng 1 query.
     *
     * {@link #getProfile} tốn khoảng 9 query (đếm follower, đếm following, trạng thái bạn thân...)
     * nhưng navbar chỉ cần avatar và id — trước đây mọi trang đều trả giá đó.
     */
    public UserProfileDto getLiteProfile(Long userId) {
        return userRepository.findById(userId).map(user -> {
            UserProfileDto dto = new UserProfileDto();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setFullName(user.getFullName());
            dto.setAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
            return dto;
        }).orElse(null);
    }

    /**
     * Dựng hồ sơ cho MỘT DANH SÁCH người bằng số query cố định (4), thay vì ~9 query mỗi người.
     *
     * Đây là đường đi dùng chung cho tìm kiếm, gợi ý, danh sách follower/following — những
     * chỗ trước đây gọi {@link #getProfile} trong vòng lặp và làm trang tải rất chậm.
     * Lưu ý: closeFriendCount KHÔNG được tính ở đây (chỉ trang cá nhân mới cần con số đó).
     */
    public List<UserProfileDto> toProfiles(List<User> users, Long currentUserId) {
        if (users == null || users.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> ids = users.stream().map(User::getId).collect(Collectors.toList());

        // 1 query: số follower của tất cả những người trong danh sách
        Map<Long, Long> followerCounts = new HashMap<>();
        for (Object[] row : friendRepository.countFollowersGrouped(ids)) {
            followerCounts.put((Long) row[0], (Long) row[1]);
        }

        Set<Long> followingSet = Collections.emptySet();
        Set<Long> followerSet = Collections.emptySet();
        Map<Long, String> closeStatuses = Collections.emptyMap();

        if (currentUserId != null) {
            // 2 query: quan hệ follow hai chiều của chính mình
            followingSet = friendRepository.findByFollowerId(currentUserId).stream()
                    .map(Friend::getFollowedId).collect(Collectors.toCollection(HashSet::new));
            followerSet = friendRepository.findByFollowedId(currentUserId).stream()
                    .map(Friend::getFollowerId).collect(Collectors.toCollection(HashSet::new));
            // 2 query: toàn bộ quan hệ bạn thân của mình
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
            dto.setCoverUrl(user.getCoverUrl());
            dto.setBio(user.getBio());
            dto.setFollowerCount(followerCounts.getOrDefault(id, 0L));

            if (currentUserId != null) {
                boolean iFollow = followingSet.contains(id);
                dto.setFollowedByCurrentUser(iFollow);
                dto.setMutualFriend(iFollow && followerSet.contains(id));
                dto.setCloseFriendStatus(closeStatuses.getOrDefault(id, "NONE"));
            }
            result.add(dto);
        }
        return result;
    }

    /** Dựng hồ sơ theo lô từ danh sách id (giữ nguyên thứ tự id truyền vào). */
    public List<UserProfileDto> getProfiles(List<Long> userIds, Long currentUserId) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, User> byId = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<User> ordered = userIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        return toProfiles(ordered, currentUserId);
    }
}