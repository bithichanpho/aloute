package com.aloute.model.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;

/**
 * "Bạn thân" (Close friend) — khác với {@link Friend} (follow một chiều, bấm là có ngay
 * kiểu Instagram/Threads), quan hệ bạn thân đi theo mô hình gửi lời mời rồi phải được
 * chấp nhận (kiểu Facebook "Add friend"). Chỉ áp dụng được giữa 2 người ĐÃ là bạn bè
 * (follow lẫn nhau) — xem {@link com.aloute.service.CloseFriendService}.
 *
 * 1 hàng = 1 lời mời, đi theo 1 chiều requester -> receiver:
 *  - status = PENDING  : đang chờ receiver phản hồi
 *  - status = ACCEPTED : đã trở thành bạn thân (hàng này chính là "bằng chứng" quan hệ,
 *    không cần thêm bảng phụ — quan hệ vốn đối xứng nên khi kiểm tra phải xét cả 2 chiều)
 * Từ chối lời mời / hủy lời mời đã gửi / xóa khỏi bạn thân đều đơn giản là xóa hàng này đi.
 */
@Entity
@Table(name = "close_friends",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_close_friends_requester_receiver", columnNames = {"requester_id", "receiver_id"})
        },
        indexes = {
                @Index(name = "idx_close_friends_requester", columnList = "requester_id, status"),
                @Index(name = "idx_close_friends_receiver", columnList = "receiver_id, status")
        })
public class CloseFriend {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "requester_id", nullable = false)
    private Long requesterId; // người gửi lời mời kết bạn thân

    @Column(name = "receiver_id", nullable = false)
    private Long receiverId;  // người nhận lời mời

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private User receiver;

    @Column(nullable = false, length = 20)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt; // null cho đến khi được chấp nhận

    public CloseFriend() {
        this.createdAt = LocalDateTime.now();
    }

    public CloseFriend(Long requesterId, Long receiverId) {
        this.requesterId = requesterId;
        this.receiverId = receiverId;
        this.status = PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRequesterId() {
        return requesterId;
    }

    public void setRequesterId(Long requesterId) {
        this.requesterId = requesterId;
    }

    public User getRequester() { return requester; }

    public User getReceiver() { return receiver; }

    public Long getReceiverId() {
        return receiverId;
    }

    public void setReceiverId(Long receiverId) {
        this.receiverId = receiverId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime respondedAt) {
        this.respondedAt = respondedAt;
    }
}