package com.aloute.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.aloute.model.entity.Community;

public interface ICommunityRepository extends JpaRepository<Community, Long> {

    /** Danh sách khám phá: hội nhóm PUBLIC, đông thành viên nhất trước. */
    List<Community> findByPrivacyOrderByMemberCountDesc(String privacy, Pageable pageable);

    List<Community> findByIdIn(List<Long> ids);

    /** Tìm theo tên (dùng cho tìm kiếm hội nhóm), không phân biệt hoa thường. */
    List<Community> findByNameContainingIgnoreCaseAndPrivacy(String keyword, String privacy, Pageable pageable);
}