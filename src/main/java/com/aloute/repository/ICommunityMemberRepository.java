package com.aloute.repository;

import com.aloute.model.entity.CommunityMember;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ICommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    List<CommunityMember> findByCommunityId(Long communityId);

    List<CommunityMember> findByUserId(Long userId);

    List<CommunityMember> findByUserIdAndRoleInOrderByJoinedAtDesc(
            Long userId,
            java.util.Collection<String> roles,
            org.springframework.data.domain.Pageable pageable);

    Optional<CommunityMember> findByCommunityIdAndUserId(Long communityId, Long userId);

    boolean existsByCommunityIdAndUserId(Long communityId, Long userId);

    boolean existsByCommunityIdAndUserIdAndRoleIn(
            Long communityId, Long userId, java.util.Collection<String> roles);

    boolean existsByCommunityIdAndRole(Long communityId, String role);

    Optional<CommunityMember> findFirstByCommunityIdAndRoleInOrderByJoinedAtAsc(
            Long communityId, java.util.Collection<String> roles);

    List<CommunityMember> findByCommunityIdAndRole(Long communityId, String role);

    List<CommunityMember> findByCommunityIdAndRole(
            Long communityId, String role, org.springframework.data.domain.Pageable pageable);

    List<CommunityMember> findByCommunityIdAndRoleInOrderByJoinedAtAsc(
            Long communityId,
            java.util.Collection<String> roles,
            org.springframework.data.domain.Pageable pageable);

    void deleteByCommunityIdAndUserId(Long communityId, Long userId);

    long countByCommunityId(Long communityId);

    long countByCommunityIdAndRoleIn(Long communityId, java.util.Collection<String> roles);

    void deleteByCommunityId(Long communityId);

    void deleteByUserId(Long userId);

    List<CommunityMember> findByUserIdAndCommunityIdIn(Long userId, List<Long> communityIds);
}
