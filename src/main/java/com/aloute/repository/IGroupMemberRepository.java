package com.aloute.repository;

import com.aloute.model.entity.GroupMember;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IGroupMemberRepository extends JpaRepository<GroupMember, Long> {

    List<GroupMember> findByGroupId(Long groupId);

    List<GroupMember> findByGroupId(Long groupId, org.springframework.data.domain.Pageable pageable);

    List<GroupMember> findByUserId(Long userId);

    List<GroupMember> findByUserId(Long userId, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query(
            "select m.groupId from GroupMember m where m.userId = :userId order by m.groupId")
    List<Long> findGroupIdsByUserId(
            @org.springframework.data.repository.query.Param("userId") Long userId);

    List<GroupMember> findByUserIdAndGroupIdIn(Long userId, java.util.Collection<Long> groupIds);

    List<GroupMember> findByUserIdInAndGroupIdIn(
            java.util.Collection<Long> userIds, java.util.Collection<Long> groupIds);

    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByUserId(Long userId);

    long countByGroupId(Long groupId);

    interface MemberCount {
        Long getGroupId();

        Long getTotal();
    }

    @org.springframework.data.jpa.repository.Query(
            "select m.groupId as groupId, count(m) as total from GroupMember m where m.groupId in"
                    + " :ids group by m.groupId")
    List<MemberCount> countMembers(
            @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
