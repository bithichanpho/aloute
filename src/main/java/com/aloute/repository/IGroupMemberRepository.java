package com.aloute.repository;

import com.aloute.model.entity.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IGroupMemberRepository extends JpaRepository<GroupMember, Long> {

    List<GroupMember> findByGroupId(Long groupId);

    List<GroupMember> findByUserId(Long userId);

    /** Batch lookup: ứng viên nào thuộc các nhóm được truyền vào. */
    List<GroupMember> findByUserIdAndGroupIdIn(Long userId, java.util.Collection<Long> groupIds);

    /** Batch lookup for a small set of candidate users across the current user's groups. */
    List<GroupMember> findByUserIdInAndGroupIdIn(java.util.Collection<Long> userIds, java.util.Collection<Long> groupIds);

    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    boolean existsByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    long countByGroupId(Long groupId);

    interface MemberCount {
        Long getGroupId();
        Long getTotal();
    }

    /** Đếm thành viên cho NHIỀU nhóm trong 1 query, thay cho N lần countByGroupId. */
    @org.springframework.data.jpa.repository.Query("select m.groupId as groupId, count(m) as total from GroupMember m where m.groupId in :ids group by m.groupId")
    List<MemberCount> countMembers(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
