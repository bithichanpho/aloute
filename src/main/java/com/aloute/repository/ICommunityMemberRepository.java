package com.aloute.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aloute.model.entity.CommunityMember;

public interface ICommunityMemberRepository extends JpaRepository<CommunityMember, Long> {

    List<CommunityMember> findByCommunityId(Long communityId);

    List<CommunityMember> findByUserId(Long userId);

    Optional<CommunityMember> findByCommunityIdAndUserId(Long communityId, Long userId);

    boolean existsByCommunityIdAndUserId(Long communityId, Long userId);

    void deleteByCommunityIdAndUserId(Long communityId, Long userId);

    long countByCommunityId(Long communityId);

    void deleteByCommunityId(Long communityId);

    /** Thành viên vào sớm nhất còn lại (dùng để tự động đôn ADMIN mới khi admin duy nhất rời nhóm). */
    List<CommunityMember> findByCommunityIdOrderByJoinedAtAsc(Long communityId);

    /**
     * Trong danh sách hội nhóm đang hiển thị, user này đã là thành viên của những nhóm nào —
     * 1 query duy nhất, dùng để tô đúng nút "Tham gia"/"Đã tham gia" theo lô, tránh N+1.
     */
    List<CommunityMember> findByUserIdAndCommunityIdIn(Long userId, List<Long> communityIds);
}