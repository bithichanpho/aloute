package com.aloute.repository;

import com.aloute.model.entity.Community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ICommunityRepository extends JpaRepository<Community, Long> {

    List<Community> findByPrivacyOrderByMemberCountDesc(String privacy, Pageable pageable);

    List<Community> findByIdIn(List<Long> ids);

    boolean existsByAvatarUrl(String avatarUrl);

    boolean existsByCoverUrl(String coverUrl);

    List<Community> findByNameContainingIgnoreCaseAndPrivacy(
            String keyword, String privacy, Pageable pageable);
}
