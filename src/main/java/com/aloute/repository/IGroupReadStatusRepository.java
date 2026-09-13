package com.aloute.repository;

import com.aloute.model.entity.GroupReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IGroupReadStatusRepository extends JpaRepository<GroupReadStatus, Long> {

    Optional<GroupReadStatus> findByGroupIdAndUserId(Long groupId, Long userId);
}