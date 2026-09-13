package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.dto.request.UpdateProfileRequest;
import com.aloute.dto.response.UserProfileDto;
import com.aloute.model.entity.User;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final IUserRepository userRepository;
    private final IFriendRepository friendRepository;
    private final FriendService friendService;
    private final CloseFriendService closeFriendService;

    public UserService(
            IUserRepository userRepository,
            IFriendRepository friendRepository,
            FriendService friendService,
            CloseFriendService closeFriendService) {
        this.userRepository = userRepository;
        this.friendRepository = friendRepository;
        this.friendService = friendService;
        this.closeFriendService = closeFriendService;
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
        dto.setAvatarUrl(user.getAvatarUrl());
        dto.setCoverUrl(user.getCoverUrl());
        dto.setBio(user.getBio());
        dto.setFollowerCount(friendRepository.countByFollowedId(targetUserId));
        dto.setFollowingCount(friendRepository.countByFollowerId(targetUserId));

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
    }

    public List<UserProfileDto> searchUsers(String keyword, Long currentUserId) {
        return userRepository.findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCase(keyword, keyword)
                .stream()
                .map(u -> getProfile(u.getId(), currentUserId))
                .collect(Collectors.toList());
    }
}