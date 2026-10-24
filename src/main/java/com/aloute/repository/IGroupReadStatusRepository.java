package com.aloute.repository;

import com.aloute.model.entity.GroupReadStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IGroupReadStatusRepository extends JpaRepository<GroupReadStatus, Long> {

    java.util.List<GroupReadStatus> findByUserId(Long userId);

    interface GroupReadTime {
        Long getGroupId();

        LocalDateTime getLastReadAt();
    }

    @Query(
            "select s.groupId as groupId, s.lastReadAt as lastReadAt from GroupReadStatus s "
                    + "where s.userId = :userId and s.groupId in :groupIds")
    List<GroupReadTime> findReadTimes(
            @Param("userId") Long userId, @Param("groupIds") Collection<Long> groupIds);

    Optional<GroupReadStatus> findByGroupIdAndUserId(Long groupId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            "update GroupReadStatus s set s.lastReadAt = :readAt "
                    + "where s.groupId = :groupId and s.userId = :userId")
    int updateLastReadAt(
            @Param("groupId") Long groupId,
            @Param("userId") Long userId,
            @Param("readAt") LocalDateTime readAt);

    void deleteByGroupIdAndUserId(Long groupId, Long userId);

    void deleteByUserId(Long userId);
}
