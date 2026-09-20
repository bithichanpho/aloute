package com.aloute.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.request.CreateCommunityRequest;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.CommunityResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;

/**
 * Nghiệp vụ Hội nhóm (Community): tạo, tham gia/rời, danh sách "của tôi"/"khám phá", và bài viết trong nhóm.
 * Bài viết thật sự nằm ở MongoDB (do {@link PostService} quản lý, gắn qua Post.communityId);
 * service này chỉ kiểm soát QUYỀN truy cập trước khi ủy quyền cho PostService.
 */
@Service
public class CommunityService {

    public static final String PRIVACY_PUBLIC = "PUBLIC";
    public static final String PRIVACY_PRIVATE = "PRIVATE";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";

    private final ICommunityRepository communityRepository;
    private final ICommunityMemberRepository memberRepository;
    private final PostService postService;

    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService) {
        this.communityRepository = communityRepository;
        this.memberRepository = memberRepository;
        this.postService = postService;
    }

    // ============================================================
    // Tạo / xem
    // ============================================================

    /** Tạo hội nhóm mới; người tạo tự động là ADMIN và là thành viên đầu tiên. */
    @Transactional
    public CommunityResponse createCommunity(Long creatorId, CreateCommunityRequest request) {
        Community community = new Community();
        community.setName(request.getName().trim());
        community.setDescription(blankToNull(request.getDescription()));
        community.setAvatarUrl(blankToNull(request.getAvatarUrl()));
        community.setCoverUrl(blankToNull(request.getCoverUrl()));
        community.setPrivacy(PRIVACY_PRIVATE.equals(request.getPrivacy()) ? PRIVACY_PRIVATE : PRIVACY_PUBLIC);
        community.setCreatedBy(creatorId);
        community.setMemberCount(1);

        Community saved = communityRepository.save(community);
        memberRepository.save(new CommunityMember(saved.getId(), creatorId, ROLE_ADMIN));

        return toResponse(saved, true, true);
    }

    /** Thông tin chung của hội nhóm. Hội nhóm riêng tư vẫn xem được phần giới thiệu (không gồm bài viết). */
    public CommunityResponse getCommunity(Long communityId, Long currentUserId) {
        Community community = getCommunityOrThrow(communityId);
        CommunityMember membership = currentUserId == null ? null
                : memberRepository.findByCommunityIdAndUserId(communityId, currentUserId).orElse(null);
        return toResponse(community, membership != null,
                membership != null && ROLE_ADMIN.equals(membership.getRole()));
    }

    /** Các hội nhóm người dùng đã tham gia, tham gia gần nhất trước. */
    public List<CommunityResponse> getMyCommunities(Long userId) {
        List<CommunityMember> memberships = new ArrayList<>(memberRepository.findByUserId(userId));
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

        memberships.sort(Comparator.comparing(CommunityMember::getJoinedAt).reversed());
        List<Long> ids = memberships.stream().map(CommunityMember::getCommunityId).collect(Collectors.toList());

        // 1 query lấy toàn bộ hội nhóm, rồi ghép lại theo thứ tự tham gia (tránh N+1).
        Map<Long, Community> communitiesById = communityRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Community::getId, c -> c));

        List<CommunityResponse> result = new ArrayList<>();
        for (CommunityMember membership : memberships) {
            Community community = communitiesById.get(membership.getCommunityId());
            if (community != null) {
                result.add(toResponse(community, true, ROLE_ADMIN.equals(membership.getRole())));
            }
        }
        return result;
    }

    /** Danh sách khám phá: hội nhóm PUBLIC đông thành viên nhất, kèm trạng thái tham gia của người xem. */
    public List<CommunityResponse> getDiscoverList(Long userId, int limit) {
        List<Community> communities = communityRepository.findByPrivacyOrderByMemberCountDesc(
                PRIVACY_PUBLIC, PageRequest.of(0, clamp(limit, 1, 50)));
        return toResponses(communities, userId);
    }

    /** Tìm hội nhóm PUBLIC theo tên. */
    public List<CommunityResponse> searchCommunities(String keyword, Long userId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<Community> communities = communityRepository.findByNameContainingIgnoreCaseAndPrivacy(
                keyword.trim(), PRIVACY_PUBLIC, PageRequest.of(0, clamp(limit, 1, 30)));
        return toResponses(communities, userId);
    }

    // ============================================================
    // Tham gia / rời
    // ============================================================

    /** Tham gia hội nhóm (idempotent: đã là thành viên thì trả về trạng thái hiện tại, không lỗi). */
    public CommunityResponse join(Long communityId, Long userId) {
        Community community = getCommunityOrThrow(communityId);

        CommunityMember existing = memberRepository.findByCommunityIdAndUserId(communityId, userId).orElse(null);
        if (existing != null) {
            return toResponse(community, true, ROLE_ADMIN.equals(existing.getRole()));
        }

        try {
            memberRepository.save(new CommunityMember(communityId, userId, ROLE_MEMBER));
        } catch (DataIntegrityViolationException ignored) {
            // 2 request "Tham gia" chạy song song: unique(community_id, user_id) đã chặn bản trùng -> coi như đã tham gia.
        }
        community = refreshMemberCount(community);

        return toResponse(community, true, false);
    }

    /**
     * Rời hội nhóm. Nếu ADMIN cuối cùng rời đi thì thành viên vào sớm nhất được đôn lên ADMIN;
     * nếu không còn ai thì hội nhóm bị xóa.
     *
     * @return thông tin hội nhóm sau khi rời, hoặc null nếu hội nhóm đã bị xóa vì không còn thành viên.
     */
    @Transactional
    public CommunityResponse leave(Long communityId, Long userId) {
        Community community = getCommunityOrThrow(communityId);
        CommunityMember membership = memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new BadRequestException("Bạn chưa tham gia hội nhóm này"));

        memberRepository.delete(membership);
        memberRepository.flush();

        List<CommunityMember> remaining = memberRepository.findByCommunityIdOrderByJoinedAtAsc(communityId);
        if (remaining.isEmpty()) {
            communityRepository.delete(community);
            return null;
        }

        boolean hasAdmin = remaining.stream().anyMatch(m -> ROLE_ADMIN.equals(m.getRole()));
        if (!hasAdmin) {
            CommunityMember successor = remaining.get(0);
            successor.setRole(ROLE_ADMIN);
            memberRepository.save(successor);
        }

        community.setMemberCount(remaining.size());
        Community saved = communityRepository.save(community);
        return toResponse(saved, false, false);
    }

    // ============================================================
    // Bài viết trong hội nhóm
    // ============================================================

    /** Feed của hội nhóm. Hội nhóm PRIVATE: chỉ thành viên mới xem được, còn lại ném UnauthorizedException. */
    public List<PostResponse> getFeed(Long communityId, Long userId, int page, int size) {
        Community community = getCommunityOrThrow(communityId);

        if (PRIVACY_PRIVATE.equals(community.getPrivacy())
                && (userId == null || !memberRepository.existsByCommunityIdAndUserId(communityId, userId))) {
            throw new UnauthorizedException("Hội nhóm riêng tư — hãy tham gia để xem bài viết");
        }

        return postService.getFeedByCommunity(String.valueOf(communityId), userId, page, size);
    }

    /** Đăng bài trong hội nhóm — chỉ thành viên mới được đăng. */
    public PostResponse createPost(Long communityId, Long userId, CreatePostRequest request) {
        getCommunityOrThrow(communityId);
        if (!memberRepository.existsByCommunityIdAndUserId(communityId, userId)) {
            throw new UnauthorizedException("Bạn cần tham gia hội nhóm để đăng bài");
        }
        return postService.createPost(userId, request, String.valueOf(communityId));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private Community getCommunityOrThrow(Long communityId) {
        return communityRepository.findById(communityId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội nhóm"));
    }

    /** Đếm lại số thành viên từ nguồn thật (bảng community_members) rồi lưu — không bị lệch khi join song song. */
    private Community refreshMemberCount(Community community) {
        community.setMemberCount((int) memberRepository.countByCommunityId(community.getId()));
        return communityRepository.save(community);
    }

    /** Map danh sách hội nhóm -> response, xác định "đã tham gia" cho cả lô bằng 1 query duy nhất. */
    private List<CommunityResponse> toResponses(List<Community> communities, Long userId) {
        if (communities.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, String> roleByCommunityId = new HashMap<>();
        if (userId != null) {
            List<Long> ids = communities.stream().map(Community::getId).collect(Collectors.toList());
            for (CommunityMember m : memberRepository.findByUserIdAndCommunityIdIn(userId, ids)) {
                roleByCommunityId.put(m.getCommunityId(), m.getRole());
            }
        }

        return communities.stream()
                .map(c -> {
                    String role = roleByCommunityId.get(c.getId());
                    return toResponse(c, role != null, ROLE_ADMIN.equals(role));
                })
                .collect(Collectors.toList());
    }

    private CommunityResponse toResponse(Community community, boolean member, boolean admin) {
        CommunityResponse dto = new CommunityResponse();
        dto.setId(community.getId());
        dto.setName(community.getName());
        dto.setDescription(community.getDescription());
        dto.setAvatarUrl(community.getAvatarUrl());
        dto.setCoverUrl(community.getCoverUrl());
        dto.setPrivacy(community.getPrivacy());
        dto.setMemberCount(community.getMemberCount());
        dto.setCreatedAt(community.getCreatedAt());
        dto.setMember(member);
        dto.setAdmin(admin);
        return dto;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}