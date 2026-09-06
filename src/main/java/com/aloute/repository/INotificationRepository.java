package com.aloute.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aloute.model.entity.Notification;

public interface INotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByReceiverIdOrderByCreatedAtDesc(Long receiverId);

    long countByReceiverIdAndReadFalse(Long receiverId);
}
