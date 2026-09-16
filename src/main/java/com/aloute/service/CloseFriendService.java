package com.aloute.service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.entity.CloseFriend;
import com.aloute.repository.ICloseFriendRepository;

/**
 * "Bạn thân" (Close friend) — mô hình gửi lời mời / chấp nhận (kiểu Facebook "Add friend"),
 * TÁCH BIỆT với Follow (vẫn một chiều, bấm là có ngay như hiện tại — xem {@link FriendService}).
 * Điều kiện tiên quyết: 2 người phải ĐÃ là bạn bè (follow lẫn nhau) mới được gửi lời mời
 * kết bạn thân — "bạn thân" là một vòng tròn nhỏ hơn, thu hẹp lại bên trong "bạn bè".
 */
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

    /**
     * Gửi lời mời kết bạn thân. Nếu đối phương đã trót gửi lời mời cho mình trước (2 lời
     * mời "đấu nhau") thì tự động chấp nhận luôn thay vì tạo thêm 1 lời mời chờ song song.
     * Trả về trạng thái SAU thao tác: "PENDING_SENT" hoặc "ACCEPTED".
     */
    public String sendRequest(Long requesterId, Long receiverId) {
        if (requesterId == null || receiverId == null || requesterId.equals(receiverId)) {
            throw new BadRequestException("Không thể tự kết bạn thân với chính mình");
        }
        if (!friendService.isFriend(requesterId, receiverId)) {
            throw new BadRequestException("Cần theo dõi lẫn nhau (là bạn bè) trước khi kết bạn thân");
        }

        Optional<CloseFriend> outgoing = closeFriendRepository.findByRequesterIdAndReceiverId(requesterId, receiverId);
        if (outgoing.isPresent()) {
            if (CloseFriend.ACCEPTED.equals(outgoing.get().getStatus())) {
                throw new BadRequestException("Hai người đã là bạn thân");
            }
            throw new BadRequestException("Bạn đã gửi lời mời kết bạn thân cho người này rồi");
        }

        // Đối phương đã gửi lời mời cho mình trước đó và đang chờ -> chấp nhận luôn
        Optional<CloseFriend> reverse = closeFriendRepository.findByRequesterIdAndReceiverId(receiverId, requesterId);
        if (reverse.isPresent() && CloseFriend.PENDING.equals(reverse.get().getStatus())) {
            return acceptRequest(requesterId, receiverId);
        }
        if (reverse.isPresent() && CloseFriend.ACCEPTED.equals(reverse.get().getStatus())) {
            throw new BadRequestException("Hai người đã là bạn thân");
        }

        closeFriendRepository.save(new CloseFriend(requesterId, receiverId));
        notificationService.notify(receiverId, requesterId, "CLOSE_FRIEND_REQUEST",
                String.valueOf(requesterId), "muốn kết bạn thân với bạn");
        return "PENDING_SENT";
    }

    /**
     * currentUserId (người NHẬN lời mời) chấp nhận lời mời đang chờ từ otherUserId.
     * Trả về "ACCEPTED".
     */
    public String acceptRequest(Long currentUserId, Long otherUserId) {
        CloseFriend request = closeFriendRepository.findByRequesterIdAndReceiverId(otherUserId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lời mời kết bạn thân này"));
        if (!CloseFriend.PENDING.equals(request.getStatus())) {
            throw new BadRequestException("Lời mời này không còn ở trạng thái chờ");
        }

        request.setStatus(CloseFriend.ACCEPTED);
        request.setRespondedAt(java.time.LocalDateTime.now());
        closeFriendRepository.save(request);

        notificationService.notify(otherUserId, currentUserId, "CLOSE_FRIEND_ACCEPTED",
                String.valueOf(currentUserId), "đã chấp nhận lời mời kết bạn thân của bạn");
        return "ACCEPTED";
    }

    /**
     * Hủy quan hệ bạn thân giữa currentUserId và otherUserId — dùng chung cho cả 3 tình
     * huống: hủy lời mời mình vừa gửi, từ chối lời mời mình vừa nhận, hoặc xóa 1 người ra
     * khỏi danh sách bạn thân đã chấp nhận từ trước. Không báo lỗi nếu vốn chẳng có quan hệ
     * nào cả (giống cách toggleFollow xử lý khi un-follow).
     */
    public void removeRelationship(Long currentUserId, Long otherUserId) {
        closeFriendRepository.findByRequesterIdAndReceiverId(currentUserId, otherUserId)
                .ifPresent(closeFriendRepository::delete);
        closeFriendRepository.findByRequesterIdAndReceiverId(otherUserId, currentUserId)
                .ifPresent(closeFriendRepository::delete);
    }

    public boolean isCloseFriend(Long userIdA, Long userIdB) {
        if (userIdA == null || userIdB == null) return false;
        return closeFriendRepository.findByRequesterIdAndReceiverId(userIdA, userIdB)
                .filter(cf -> CloseFriend.ACCEPTED.equals(cf.getStatus())).isPresent()
                || closeFriendRepository.findByRequesterIdAndReceiverId(userIdB, userIdA)
                .filter(cf -> CloseFriend.ACCEPTED.equals(cf.getStatus())).isPresent();
    }

    /**
     * Trạng thái quan hệ bạn thân nhìn từ góc độ currentUserId đối với otherUserId:
     * "NONE" | "PENDING_SENT" (mình vừa gửi, đang chờ) | "PENDING_RECEIVED" (đối phương
     * vừa gửi cho mình, đang chờ mình phản hồi) | "ACCEPTED" (đã là bạn thân).
     */
    public String getStatus(Long currentUserId, Long otherUserId) {
        if (currentUserId == null || otherUserId == null || currentUserId.equals(otherUserId)) {
            return "NONE";
        }

        Optional<CloseFriend> outgoing = closeFriendRepository.findByRequesterIdAndReceiverId(currentUserId, otherUserId);
        if (outgoing.isPresent()) {
            return CloseFriend.ACCEPTED.equals(outgoing.get().getStatus()) ? "ACCEPTED" : "PENDING_SENT";
        }

        Optional<CloseFriend> incoming = closeFriendRepository.findByRequesterIdAndReceiverId(otherUserId, currentUserId);
        if (incoming.isPresent()) {
            return CloseFriend.ACCEPTED.equals(incoming.get().getStatus()) ? "ACCEPTED" : "PENDING_RECEIVED";
        }

        return "NONE";
    }

    /**
     * Toàn bộ quan hệ bạn thân của một người, gói trong 2 query, dưới dạng
     * map otherUserId -> trạng thái nhìn từ góc độ người đó.
     *
     * Dùng khi cần dựng nhiều hồ sơ cùng lúc (tìm kiếm, gợi ý, danh sách follower):
     * gọi {@link #getStatus} cho từng người sẽ tốn 2 query mỗi người.
     */
    public Map<Long, String> getStatusMap(Long currentUserId) {
        if (currentUserId == null) {
            return Map.of();
        }

        Map<Long, String> statuses = new HashMap<>();
        for (CloseFriend cf : closeFriendRepository.findByRequesterId(currentUserId)) {
            statuses.put(cf.getReceiverId(),
                    CloseFriend.ACCEPTED.equals(cf.getStatus()) ? "ACCEPTED" : "PENDING_SENT");
        }
        for (CloseFriend cf : closeFriendRepository.findByReceiverId(currentUserId)) {
            String incoming = CloseFriend.ACCEPTED.equals(cf.getStatus()) ? "ACCEPTED" : "PENDING_RECEIVED";
            // Nếu tồn tại quan hệ ở cả 2 chiều thì ACCEPTED luôn thắng
            statuses.merge(cf.getRequesterId(), incoming, (a, b) -> "ACCEPTED".equals(a) ? a : b);
        }
        return statuses;
    }

    /** Danh sách userId đang là bạn thân (đã ACCEPTED, xét cả 2 chiều) với userId. */
    public List<Long> getCloseFriendIds(Long userId) {
        Set<Long> ids = new LinkedHashSet<>();
        closeFriendRepository.findByRequesterIdAndStatus(userId, CloseFriend.ACCEPTED)
                .forEach(cf -> ids.add(cf.getReceiverId()));
        closeFriendRepository.findByReceiverIdAndStatus(userId, CloseFriend.ACCEPTED)
                .forEach(cf -> ids.add(cf.getRequesterId()));
        return List.copyOf(ids);
    }
}