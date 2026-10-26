package com.aloute.repository;

import com.aloute.model.entity.UserPushToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IUserPushTokenRepository extends JpaRepository<UserPushToken, Long> {
    List<UserPushToken> findAllByUserId(Long userId);
    Optional<UserPushToken> findByFid(String fid);
    void deleteByUserIdAndFid(Long userId, String fid);
    void deleteByUserId(Long userId);
    void deleteByFid(String fid);
}
