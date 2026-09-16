package com.aloute.repository;

import com.aloute.model.entity.GroupReadStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IGroupReadStatusRepository extends JpaRepository<GroupReadStatus, Long> {

    /** Lấy mốc đã đọc của TẤT CẢ nhóm trong 1 query — dùng khi dựng danh sách hội thoại. */
    java.util.List<GroupReadStatus> findByUserId(Long userId);

    Optional<GroupReadStatus> findByGroupIdAndUserId(Long groupId, Long userId);
}
