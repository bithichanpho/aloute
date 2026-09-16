package com.aloute.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aloute.model.entity.Notification;

public interface INotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByReceiverIdOrderByCreatedAtDesc(Long receiverId,
            org.springframework.data.domain.Pageable pageable);

    long countByReceiverIdAndReadFalse(Long receiverId);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update Notification n set n.read = true where n.receiverId = :userId and n.read = false")
    int markAllRead(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * Xóa thông báo like/comment gắn với một bài viết đã bị xóa.
     * Derived-delete của JPA bắt buộc chạy trong transaction, nên khai báo sẵn ở đây.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    long deleteByReferenceId(String referenceId);
}
