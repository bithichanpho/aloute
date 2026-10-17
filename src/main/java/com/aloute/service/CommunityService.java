package com.aloute.service;

import com.aloute.common.util.TransactionCommitHooks;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.dto.request.CreateCommunityRequest;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.CommunityResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CommunityService {

    public static final String PRIVACY_PUBLIC = "PUBLIC";
    public static final String PRIVACY_PRIVATE = "PRIVATE";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_PENDING = "PENDING";
    public static final String ROLE_BANNED = "BANNED";

    private final ICommunityRepository communityRepository;
    private final ICommunityMemberRepository memberRepository;
    private final PostService postService;
    private final MediaUploadModerationService mediaUploadModeration;
    private final MediaAssetLifecycleService mediaAssets;

    @Autowired
    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService,
            MediaUploadModerationService mediaUploadModeration,
            MediaAssetLifecycleService mediaAssets) {
        this.communityRepository = communityRepository;
        this.memberRepository = memberRepository;
        this.postService = postService;
        this.mediaUploadModeration = mediaUploadModeration;
        this.mediaAssets = mediaAssets;
    }

    /** Compatibility constructor for service tests and older callers. */
    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService) {
        this(communityRepository, memberRepository, postService, null, null);
    }

    @Transactional
    public CommunityResponse createCommunity(Long creatorId, CreateCommunityRequest request) {
        validateMediaPurpose(request, null, creatorId);
        Community community = new Community();
        community.setName(request.getName().trim());
        community.setDescription(blankToNull(request.getDescription()));
        community.setAvatarUrl(blankToNull(request.getAvatarUrl()));
        community.setCoverUrl(blankToNull(request.getCoverUrl()));
        community.setPrivacy(
                PRIVACY_PRIVATE.equals(request.getPrivacy()) ? PRIVACY_PRIVATE : PRIVACY_PUBLIC);
        community.setCreatedBy(creatorId);
        community.setMemberCount(1);

        Community saved = communityRepository.save(community);
        memberRepository.save(new CommunityMember(saved.getId(), creatorId, ROLE_ADMIN));
        afterCommitMediaLifecycle(saved, null, null, creatorId);

        return toResponse(saved, true, true);
    }

    public CommunityResponse getCommunity(Long communityId, Long currentUserId) {
        Community community = getCommunityOrThrow(communityId);
        CommunityMember membership =
                currentUserId == null
                        ? null
                        : memberRepository
                                .findByCommunityIdAndUserId(communityId, currentUserId)
                                .orElse(null);
        return toResponse(
                community,
                isActiveMember(membership),
                membership != null && ROLE_ADMIN.equals(membership.getRole()));
    }

    public List<CommunityResponse> getMyCommunities(Long userId) {
        List<CommunityMember> memberships =
                memberRepository.findByUserId(userId).stream()
                        .filter(this::isActiveMember)
                        .collect(Collectors.toCollection(ArrayList::new));
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

        memberships.sort(Comparator.comparing(CommunityMember::getJoinedAt).reversed());
        List<Long> ids =
                memberships.stream()
                        .map(CommunityMember::getCommunityId)
                        .collect(Collectors.toList());

        Map<Long, Community> communitiesById =
                communityRepository.findByIdIn(ids).stream()
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

    public List<CommunityResponse> getDiscoverList(Long userId, int limit) {
        List<Community> communities =
                communityRepository.findByPrivacyOrderByMemberCountDesc(
                        PRIVACY_PUBLIC, PageRequest.of(0, clamp(limit, 1, 50)));
        return toResponses(communities, userId);
    }

    public List<CommunityResponse> searchCommunities(String keyword, Long userId, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return Collections.emptyList();
        }
        List<Community> communities =
                communityRepository.findByNameContainingIgnoreCaseAndPrivacy(
                        keyword.trim(), PRIVACY_PUBLIC, PageRequest.of(0, clamp(limit, 1, 30)));
        return toResponses(communities, userId);
    }

    public CommunityResponse join(Long communityId, Long userId) {
        Community community = getCommunityOrThrow(communityId);

        CommunityMember existing =
                memberRepository.findByCommunityIdAndUserId(communityId, userId).orElse(null);
        if (existing != null) {
            if (ROLE_BANNED.equals(existing.getRole())) {
                throw new ForbiddenException("Bạn đã bị cấm khỏi hội nhóm này");
            }
            return toResponse(
                    community, isActiveMember(existing), ROLE_ADMIN.equals(existing.getRole()));
        }

        try {
            memberRepository.save(
                    new CommunityMember(
                            communityId,
                            userId,
                            PRIVACY_PRIVATE.equals(community.getPrivacy())
                                    ? ROLE_PENDING
                                    : ROLE_MEMBER));
        } catch (DataIntegrityViolationException ignored) {

        }
        if (PRIVACY_PRIVATE.equals(community.getPrivacy())) {
            return toResponse(community, false, false);
        }
        community = refreshMemberCount(community);

        return toResponse(community, true, false);
    }

    @Transactional
    public CommunityResponse leave(Long communityId, Long userId) {
        Community community = getCommunityOrThrow(communityId);
        CommunityMember membership =
                memberRepository
                        .findByCommunityIdAndUserId(communityId, userId)
                        .orElseThrow(
                                () -> new BadRequestException("Bạn chưa tham gia hội nhóm này"));

        memberRepository.delete(membership);
        memberRepository.flush();

        List<CommunityMember> remaining =
                memberRepository.findByCommunityIdOrderByJoinedAtAsc(communityId).stream()
                        .filter(this::isActiveMember)
                        .toList();
        if (remaining.isEmpty()) {
            String avatarUrl = community.getAvatarUrl();
            String coverUrl = community.getCoverUrl();
            memberRepository.deleteByCommunityId(communityId);
            communityRepository.delete(community);
            TransactionCommitHooks.afterCommit(() -> {
                deleteIfUnreferencedSafely(avatarUrl, communityId);
                deleteIfUnreferencedSafely(coverUrl, communityId);
            });
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

    public List<PostResponse> getFeed(Long communityId, Long userId, int page, int size) {
        Community community = getCommunityOrThrow(communityId);

        if (PRIVACY_PRIVATE.equals(community.getPrivacy())
                && (userId == null
                        || !isActiveMember(communityId, userId))) {
            throw new ForbiddenException("Hội nhóm riêng tư — hãy tham gia để xem bài viết");
        }

        return postService.getFeedByCommunity(String.valueOf(communityId), userId, page, size);
    }

    public PostResponse createPost(Long communityId, Long userId, CreatePostRequest request) {
        getCommunityOrThrow(communityId);
        if (!isActiveMember(communityId, userId)) {
            throw new ForbiddenException("Bạn cần tham gia hội nhóm để đăng bài");
        }
        return postService.createPost(userId, request, String.valueOf(communityId));
    }

    public List<CommunityMember> pendingMembers(Long communityId, Long requesterId) {
        requireAdmin(communityId, requesterId);
        return memberRepository.findByCommunityIdAndRole(communityId, ROLE_PENDING);
    }

    @Transactional
    public void decideMember(Long communityId, Long requesterId, Long targetId, boolean accept) {
        requireAdmin(communityId, requesterId);
        CommunityMember member =
                memberRepository
                        .findByCommunityIdAndUserId(communityId, targetId)
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu tham gia"));
        if (!ROLE_PENDING.equals(member.getRole())) {
            throw new BadRequestException("Yêu cầu tham gia không còn chờ duyệt");
        }
        if (accept) {
            member.setRole(ROLE_MEMBER);
            memberRepository.save(member);
            refreshMemberCount(getCommunityOrThrow(communityId));
        } else {
            memberRepository.delete(member);
        }
    }

    @Transactional
    public void banMember(Long communityId, Long requesterId, Long targetId, boolean banned) {
        requireAdmin(communityId, requesterId);
        if (requesterId.equals(targetId)) throw new BadRequestException("Không thể tự cấm chính mình");
        CommunityMember member =
                memberRepository
                        .findByCommunityIdAndUserId(communityId, targetId)
                        .orElseGet(() -> new CommunityMember(communityId, targetId, ROLE_BANNED));
        member.setRole(banned ? ROLE_BANNED : ROLE_MEMBER);
        memberRepository.save(member);
        refreshMemberCount(getCommunityOrThrow(communityId));
    }

    @Transactional
    public CommunityResponse updateCommunity(
            Long communityId, Long requesterId, CreateCommunityRequest request) {
        requireAdmin(communityId, requesterId);
        Community community = getCommunityOrThrow(communityId);
        // Existing legacy URLs may remain unchanged, but newly supplied URLs must come through
        // the community upload endpoint and be owned by the authenticated uploader.
        String oldAvatarUrl = community.getAvatarUrl();
        String oldCoverUrl = community.getCoverUrl();
        validateMediaPurpose(request, community, requesterId);
        community.setName(request.getName().trim());
        community.setDescription(blankToNull(request.getDescription()));
        community.setAvatarUrl(blankToNull(request.getAvatarUrl()));
        community.setCoverUrl(blankToNull(request.getCoverUrl()));
        community.setPrivacy(
                PRIVACY_PRIVATE.equals(request.getPrivacy()) ? PRIVACY_PRIVATE : PRIVACY_PUBLIC);
        Community saved = communityRepository.save(community);
        afterCommitMediaLifecycle(saved, oldAvatarUrl, oldCoverUrl, requesterId);
        return toResponse(saved, true, true);
    }

    @Transactional
    public void deleteCommunity(Long communityId, Long requesterId) {
        requireAdmin(communityId, requesterId);
        Community community = getCommunityOrThrow(communityId);
        String avatarUrl = community.getAvatarUrl();
        String coverUrl = community.getCoverUrl();
        memberRepository.deleteByCommunityId(communityId);
        communityRepository.delete(community);
        TransactionCommitHooks.afterCommit(() -> {
            deleteIfUnreferencedSafely(avatarUrl, communityId);
            deleteIfUnreferencedSafely(coverUrl, communityId);
        });
    }

    private void validateMediaPurpose(CreateCommunityRequest request, Community existing, Long uploaderId) {
        if (request == null) return;
        validateCommunityImageUrl(request.getAvatarUrl(), existing == null ? null : existing.getAvatarUrl(), uploaderId);
        validateCommunityImageUrl(request.getCoverUrl(), existing == null ? null : existing.getCoverUrl(), uploaderId);
    }

    private void validateCommunityImageUrl(String submittedUrl, String existingUrl, Long uploaderId) {
        if (submittedUrl == null || submittedUrl.isBlank() || submittedUrl.equals(existingUrl)) return;
        if (mediaUploadModeration == null) {
            throw new BadRequestException("Không thể xác minh media hội nhóm; vui lòng tải lên lại");
        }
        mediaUploadModeration.requirePurposeUrl(
                submittedUrl, UploadPurpose.COMMUNITY, String.valueOf(uploaderId), "image");
    }

    private void afterCommitMediaLifecycle(
            Community saved, String oldAvatarUrl, String oldCoverUrl, Long uploaderId) {
        if (mediaAssets == null || saved == null) return;
        TransactionCommitHooks.afterCommit(() -> {
            try {
                mediaAssets.attachManagedReference(saved.getAvatarUrl(), UploadPurpose.COMMUNITY,
                        String.valueOf(uploaderId), "COMMUNITY_AVATAR", String.valueOf(saved.getId()));
                mediaAssets.attachManagedReference(saved.getCoverUrl(), UploadPurpose.COMMUNITY,
                        String.valueOf(uploaderId), "COMMUNITY_COVER", String.valueOf(saved.getId()));
                if (oldAvatarUrl != null && !oldAvatarUrl.equals(saved.getAvatarUrl())) {
                    mediaAssets.deleteIfUnreferenced(oldAvatarUrl);
                }
                if (oldCoverUrl != null && !oldCoverUrl.equals(saved.getCoverUrl())) {
                    mediaAssets.deleteIfUnreferenced(oldCoverUrl);
                }
            } catch (RuntimeException cleanupFailure) {
                org.slf4j.LoggerFactory.getLogger(CommunityService.class).warn(
                        "Community media lifecycle reconciliation deferred (communityId={}, cause={})",
                        saved.getId(), cleanupFailure.getClass().getSimpleName());
            }
        });
    }

    private void deleteIfUnreferencedSafely(String url, Long communityId) {
        if (mediaAssets == null || url == null || url.isBlank()) return;
        try {
            mediaAssets.deleteIfUnreferenced(url);
        } catch (RuntimeException cleanupFailure) {
            org.slf4j.LoggerFactory.getLogger(CommunityService.class).warn(
                    "Community media cleanup deferred (communityId={}, cause={})",
                    communityId, cleanupFailure.getClass().getSimpleName());
        }
    }

    private void requireAdmin(Long communityId, Long userId) {
        CommunityMember membership = memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new ForbiddenException("Bạn không phải quản trị viên hội nhóm"));
        if (!ROLE_ADMIN.equals(membership.getRole())) {
            throw new ForbiddenException("Chỉ quản trị viên hội nhóm được thực hiện thao tác này");
        }
    }

    private boolean isActiveMember(Long communityId, Long userId) {
        return memberRepository.existsByCommunityIdAndUserIdAndRoleIn(
                communityId, userId, List.of(ROLE_ADMIN, ROLE_MEMBER));
    }

    private boolean isActiveMember(CommunityMember membership) {
        return membership != null
                && (ROLE_ADMIN.equals(membership.getRole()) || ROLE_MEMBER.equals(membership.getRole()));
    }

    private Community getCommunityOrThrow(Long communityId) {
        return communityRepository
                .findById(communityId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy hội nhóm"));
    }

    private Community refreshMemberCount(Community community) {
        community.setMemberCount(
                (int)
                        memberRepository.countByCommunityIdAndRoleIn(
                                community.getId(), List.of(ROLE_ADMIN, ROLE_MEMBER)));
        return communityRepository.save(community);
    }

    private List<CommunityResponse> toResponses(List<Community> communities, Long userId) {
        if (communities.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, String> roleByCommunityId = new HashMap<>();
        if (userId != null) {
            List<Long> ids =
                    communities.stream().map(Community::getId).collect(Collectors.toList());
            for (CommunityMember m : memberRepository.findByUserIdAndCommunityIdIn(userId, ids)) {
                roleByCommunityId.put(m.getCommunityId(), m.getRole());
            }
        }

        return communities.stream()
                .map(
                        c -> {
                            String role = roleByCommunityId.get(c.getId());
                            return toResponse(
                                    c,
                                    ROLE_ADMIN.equals(role) || ROLE_MEMBER.equals(role),
                                    ROLE_ADMIN.equals(role));
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
