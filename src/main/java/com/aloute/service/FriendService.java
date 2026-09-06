package com.aloute.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.entity.Friend;
import com.aloute.repository.IFriendRepository;

@Service
public class FriendService {

    private final IFriendRepository friendRepository;
    private final NotificationService notificationService;

    public FriendService(IFriendRepository friendRepository, NotificationService notificationService) {
        this.friendRepository = friendRepository;
        this.notificationService = notificationService;
    }

    /** Trả về true nếu SAU thao tác là ĐANG follow. */
    public boolean toggleFollow(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new BadRequestException("Không thể tự follow chính mình");
        }

        return friendRepository.findByFollowerIdAndFollowedId(currentUserId, targetUserId)
                .map(existing -> {
                    friendRepository.delete(existing);
                    return false;
                })
                .orElseGet(() -> {
                    friendRepository.save(new Friend(currentUserId, targetUserId));
                    notificationService.notify(targetUserId, currentUserId, "FOLLOW", null, "đã theo dõi bạn");
                    return true;
                });
    }

    public List<Long> getFollowingIds(Long userId) {
        return friendRepository.findByFollowerId(userId).stream()
                .map(Friend::getFollowedId)
                .collect(Collectors.toList());
    }

    public List<Long> getFollowerIds(Long userId) {
        return friendRepository.findByFollowedId(userId).stream()
                .map(Friend::getFollowerId)
                .collect(Collectors.toList());
    }
}