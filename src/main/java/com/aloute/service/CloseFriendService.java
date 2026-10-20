package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.entity.CloseFriend;
import com.aloute.repository.ICloseFriendRepository;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class CloseFriendService {

    private final ICloseFriendRepository closeFriendRepository;
    private final FriendService friendService;
    private final NotificationService notificationService;

    public CloseFriendService(
            ICloseFriendRepository closeFriendRepository,
            FriendService friendService,
            NotificationService notificationService) {
        this.closeFriendRepository = closeFriendRepository;
        this.friendService = friendService;
        this.notificationService = notificationService;
    }

    @org.springframework.transaction.annotation.Transactional
    public String sendRequest(Long requesterId, Long receiverId) {
        if (requesterId == null || receiverId == null || requesterId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự kết bạn thân với chính mình");
        }
        if (!friendService.isFriend(requesterId, receiverId)) {
            throw new BadRequestException(
                    "Cần theo dõi lẫn nhau (là bạn bè) trước khi kết bạn thân");
        }

        Optional<CloseFriend> outgoing =
                closeFriendRepository.findByRequesterIdAndReceiverId(requesterId, receiverId);
        if (outgoing.isPresent()) {
            if (CloseFriend.ACCEPTED.equals(outgoing.get().getStatus())) {
                throw new BadRequestException("Hai người đã là bạn thân");
            }
            throw new BadRequestException("Bạn đã gửi lời mời kết bạn thân cho người này rồi");
        }

        Optional<CloseFriend> reverse =
                closeFriendRepository.findByRequesterIdAndReceiverId(receiverId, requesterId);
        if (reverse.isPresent() && CloseFriend.PENDING.equals(reverse.get().getStatus())) {
            return acceptRequest(requesterId, receiverId);
        }
        if (reverse.isPresent() && CloseFriend.ACCEPTED.equals(reverse.get().getStatus())) {
            throw new BadRequestException("Hai người đã là bạn thân");
        }

        closeFriendRepository.save(new CloseFriend(requesterId, receiverId));
        notificationService.notify(
                receiverId,
                requesterId,
                "CLOSE_FRIEND_REQUEST",
                String.valueOf(requesterId),
                "muốn kết bạn thân với bạn");
        return "PENDING_SENT";
    }

    @org.springframework.transaction.annotation.Transactional
    public String acceptRequest(Long currentUserId, Long otherUserId) {
        if (!friendService.isFriend(currentUserId, otherUserId))
            throw new BadRequestException("Hai tài khoản cần theo dõi lẫn nhau");
        CloseFriend request =
                closeFriendRepository
                        .findByRequesterIdAndReceiverId(otherUserId, currentUserId)
                        .orElseThrow(
                                () ->
                                        new ResourceNotFoundException(
                                                "Không tìm thấy lời mời kết bạn thân này"));
        if (!CloseFriend.PENDING.equals(request.getStatus())) {
            throw new BadRequestException("Lời mời này không còn ở trạng thái chờ");
        }

        request.setStatus(CloseFriend.ACCEPTED);
        request.setRespondedAt(java.time.LocalDateTime.now());
        closeFriendRepository.save(request);

        notificationService.notify(
                otherUserId,
                currentUserId,
                "CLOSE_FRIEND_ACCEPTED",
                String.valueOf(currentUserId),
                "đã chấp nhận lời mời kết bạn thân của bạn");
        return "ACCEPTED";
    }

    @org.springframework.transaction.annotation.Transactional
    public void removeRelationship(Long currentUserId, Long otherUserId) {
        closeFriendRepository
                .findByRequesterIdAndReceiverId(currentUserId, otherUserId)
                .ifPresent(closeFriendRepository::delete);
        closeFriendRepository
                .findByRequesterIdAndReceiverId(otherUserId, currentUserId)
                .ifPresent(closeFriendRepository::delete);
    }

    public boolean isCloseFriend(Long userIdA, Long userIdB) {
        if (userIdA == null || userIdB == null) return false;
        return closeFriendRepository
                        .findByRequesterIdAndReceiverId(userIdA, userIdB)
                        .filter(cf -> CloseFriend.ACCEPTED.equals(cf.getStatus()))
                        .isPresent()
                || closeFriendRepository
                        .findByRequesterIdAndReceiverId(userIdB, userIdA)
                        .filter(cf -> CloseFriend.ACCEPTED.equals(cf.getStatus()))
                        .isPresent();
    }

    public String getStatus(Long currentUserId, Long otherUserId) {
        if (currentUserId == null || otherUserId == null || currentUserId.equals(otherUserId)) {
            return "NONE";
        }

        Optional<CloseFriend> outgoing =
                closeFriendRepository.findByRequesterIdAndReceiverId(currentUserId, otherUserId);
        if (outgoing.isPresent()) {
            return CloseFriend.ACCEPTED.equals(outgoing.get().getStatus())
                    ? "ACCEPTED"
                    : "PENDING_SENT";
        }

        Optional<CloseFriend> incoming =
                closeFriendRepository.findByRequesterIdAndReceiverId(otherUserId, currentUserId);
        if (incoming.isPresent()) {
            return CloseFriend.ACCEPTED.equals(incoming.get().getStatus())
                    ? "ACCEPTED"
                    : "PENDING_RECEIVED";
        }

        return "NONE";
    }

    public Map<Long, String> getStatusMap(Long currentUserId) {
        if (currentUserId == null) {
            return Map.of();
        }

        Map<Long, String> statuses = new HashMap<>();
        for (CloseFriend cf : closeFriendRepository.findByRequesterId(currentUserId)) {
            statuses.put(
                    cf.getReceiverId(),
                    CloseFriend.ACCEPTED.equals(cf.getStatus()) ? "ACCEPTED" : "PENDING_SENT");
        }
        for (CloseFriend cf : closeFriendRepository.findByReceiverId(currentUserId)) {
            String incoming =
                    CloseFriend.ACCEPTED.equals(cf.getStatus()) ? "ACCEPTED" : "PENDING_RECEIVED";

            statuses.merge(cf.getRequesterId(), incoming, (a, b) -> "ACCEPTED".equals(a) ? a : b);
        }
        return statuses;
    }

    public Map<Long, String> getStatusMap(Long currentUserId, java.util.Collection<Long> otherIds) {
        if (currentUserId == null || otherIds == null || otherIds.isEmpty()) return Map.of();
        Map<Long, String> statuses = new HashMap<>();
        for (CloseFriend relationship : closeFriendRepository.findRelationships(currentUserId, otherIds)) {
            Long otherId = currentUserId.equals(relationship.getRequesterId())
                    ? relationship.getReceiverId() : relationship.getRequesterId();
            String status = CloseFriend.ACCEPTED.equals(relationship.getStatus())
                    ? "ACCEPTED"
                    : currentUserId.equals(relationship.getRequesterId())
                            ? "PENDING_SENT" : "PENDING_RECEIVED";
            statuses.merge(otherId, status, (left, right) -> "ACCEPTED".equals(left) ? left : right);
        }
        return statuses;
    }

    public List<Long> getCloseFriendIds(Long userId) {
        return getCloseFriendIds(userId, 0, 100);
    }

    public List<Long> getCloseFriendIds(Long userId, int page, int size) {
        return closeFriendRepository.findRelatedUserIds(
                userId, CloseFriend.ACCEPTED,
                org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), Math.max(1, Math.min(100, size))));
    }

    public long countCloseFriends(Long userId) {
        return closeFriendRepository.countRelationships(userId, CloseFriend.ACCEPTED);
    }
}
