package com.aloute.service;

import com.aloute.common.util.TransactionCommitHooks;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.dto.request.CreateCommunityRequest;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.dto.response.CommunityResponse;
import com.aloute.dto.response.CommunityMemberResponse;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.Comment;
import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.document.Post;
import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.model.entity.User;
import com.aloute.repository.ICommunityMemberRepository;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.common.util.AvatarUtil;

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
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_MANAGER = "MANAGER";
    /** Legacy role retained while old rows are migrated to OWNER. */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_MEMBER = "MEMBER";
    public static final String ROLE_PENDING = "PENDING";
    public static final String ROLE_BANNED = "BANNED";

    private final ICommunityRepository communityRepository;
    private final ICommunityMemberRepository memberRepository;
    private final PostService postService;
    private final MediaUploadModerationService mediaUploadModeration;
    private final MediaAssetLifecycleService mediaAssets;
    private final IUserRepository userRepository;
    private final NotificationService notificationService;
    private final CommentService commentService;
    private final CommunityCleanupOutboxService cleanupOutbox;

    @Autowired
    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService,
            MediaUploadModerationService mediaUploadModeration,
            MediaAssetLifecycleService mediaAssets,
            IUserRepository userRepository,
            NotificationService notificationService,
            CommentService commentService,
            CommunityCleanupOutboxService cleanupOutbox) {
        this.communityRepository = communityRepository;
        this.memberRepository = memberRepository;
        this.postService = postService;
        this.mediaUploadModeration = mediaUploadModeration;
        this.mediaAssets = mediaAssets;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.commentService = commentService;
        this.cleanupOutbox = cleanupOutbox;
    }

    /** Compatibility constructor for focused service tests and older callers. */
    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService,
            MediaUploadModerationService mediaUploadModeration,
            MediaAssetLifecycleService mediaAssets,
            IUserRepository userRepository,
            NotificationService notificationService,
            CommentService commentService) {
        this(communityRepository, memberRepository, postService, mediaUploadModeration,
                mediaAssets, userRepository, notificationService, commentService, null);
    }

    /** Compatibility constructor for service tests and older callers. */
    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService,
            MediaUploadModerationService mediaUploadModeration,
            MediaAssetLifecycleService mediaAssets) {
        this(communityRepository, memberRepository, postService, mediaUploadModeration,
                mediaAssets, null, null, null, null);
    }

    /** Compatibility constructor for service tests and older callers. */
    public CommunityService(
            ICommunityRepository communityRepository,
            ICommunityMemberRepository memberRepository,
            PostService postService) {
        this(communityRepository, memberRepository, postService, null, null, null, null, null, null);
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
        memberRepository.save(new CommunityMember(saved.getId(), creatorId, ROLE_OWNER));
        afterCommitMediaLifecycle(saved, null, null, creatorId);

        return toResponse(saved, ROLE_OWNER);
    }

    public CommunityResponse getCommunity(Long communityId, Long currentUserId) {
        Community community = getCommunityOrThrow(communityId);
        CommunityMember membership =
                currentUserId == null
                        ? null
                        : memberRepository
                                .findByCommunityIdAndUserId(communityId, currentUserId)
                                .orElse(null);
        return toResponse(community, membership == null ? null : membership.getRole());
    }

    public List<CommunityResponse> getMyCommunities(Long userId) {
        return getMyCommunities(userId, 0, 100);
    }

    public List<CommunityResponse> getMyCommunities(Long userId, int page, int size) {
        List<CommunityMember> memberships =
                memberRepository.findByUserIdAndRoleInOrderByJoinedAtDesc(
                                userId, activeRoles(), PageRequest.of(page, size))
                        .stream()
                        .collect(Collectors.toCollection(ArrayList::new));
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

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
                result.add(toResponse(community, membership.getRole()));
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
            return toResponse(community, existing.getRole());
        }

        try {
            memberRepository.save(
                    new CommunityMember(
                            communityId,
                            userId,
                            PRIVACY_PRIVATE.equals(community.getPrivacy())
                                    ? ROLE_PENDING
                                    : ROLE_MEMBER));
        } catch (DataIntegrityViolationException concurrentJoin) {
            // A simultaneous join may win the unique (community,user) insert. Treat it as
            // idempotent only when that exact membership now exists; do not hide unrelated FK or
            // database failures and falsely tell the caller that joining succeeded.
            CommunityMember persisted = memberRepository
                    .findByCommunityIdAndUserId(communityId, userId)
                    .orElseThrow(() -> concurrentJoin);
            if (ROLE_BANNED.equals(persisted.getRole())) {
                throw new ForbiddenException("Bạn đã bị cấm khỏi hội nhóm này");
            }
            if (isActiveMember(persisted)) community = refreshMemberCount(community);
            return toResponse(community, persisted.getRole());
        }
        if (PRIVACY_PRIVATE.equals(community.getPrivacy())) {
            return toResponse(community, ROLE_PENDING);
        }
        community = refreshMemberCount(community);

        return toResponse(community, ROLE_MEMBER);
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

        long remainingCount = memberRepository.countByCommunityIdAndRoleIn(
                communityId, activeRoles());
        if (remainingCount == 0) {
            String avatarUrl = community.getAvatarUrl();
            String coverUrl = community.getCoverUrl();
            enqueueCleanupOrUseCompatibilityFallback(communityId, avatarUrl, coverUrl);
            memberRepository.deleteByCommunityId(communityId);
            communityRepository.delete(community);
            return null;
        }

        if (!hasOwner(communityId)) {
            CommunityMember successor = memberRepository
                    .findFirstByCommunityIdAndRoleInOrderByJoinedAtAsc(
                            communityId, List.of(ROLE_MANAGER))
                    .or(() -> memberRepository.findFirstByCommunityIdAndRoleInOrderByJoinedAtAsc(
                            communityId, List.of(ROLE_MEMBER)))
                    .orElseThrow(() -> new IllegalStateException(
                            "Community has active members but no eligible owner successor"));
            successor.setRole(ROLE_OWNER);
            memberRepository.save(successor);
        }

        community.setMemberCount(Math.toIntExact(remainingCount));
        Community saved = communityRepository.save(community);
        return toResponse(saved, null);
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
        CommunityMember membership = memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .filter(this::isActiveMember)
                .orElseThrow(() -> new ForbiddenException("Bạn cần tham gia hội nhóm để đăng bài"));
        CommunityPostStatus reviewStatus = isManagerRole(membership.getRole())
                ? CommunityPostStatus.APPROVED
                : CommunityPostStatus.PENDING;
        PostResponse response = postService.createPost(
                userId, request, String.valueOf(communityId), reviewStatus);
        if (reviewStatus == CommunityPostStatus.PENDING) {
            notifyManagersOfPendingPost(communityId, userId, response.getId());
        }
        return response;
    }

    public List<CommunityMemberResponse> members(
            Long communityId, Long requesterId, int page, int size) {
        requireManager(communityId, requesterId);
        List<CommunityMember> memberships = memberRepository
                .findByCommunityIdAndRoleInOrderByJoinedAtAsc(
                        communityId,
                        List.of(ROLE_OWNER, ROLE_MANAGER, ROLE_ADMIN, ROLE_MEMBER, ROLE_BANNED),
                        PageRequest.of(page, size));
        return toMemberResponses(memberships);
    }

    @Transactional
    public void setManager(
            Long communityId, Long requesterId, Long targetId, boolean manager) {
        requireOwner(communityId, requesterId);
        if (requesterId.equals(targetId)) {
            throw new BadRequestException("Chủ nhóm không thể tự thay đổi vai trò của mình");
        }
        CommunityMember target = memberRepository.findByCommunityIdAndUserId(communityId, targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thành viên"));
        if (isOwnerRole(target.getRole())) {
            throw new BadRequestException("Không thể thay đổi vai trò của chủ nhóm");
        }
        String expectedRole = manager ? ROLE_MEMBER : ROLE_MANAGER;
        if (!expectedRole.equals(target.getRole())) {
            throw new BadRequestException(manager
                    ? "Chỉ thành viên thường mới có thể được bổ nhiệm"
                    : "Người dùng này không phải quản lý cộng đồng");
        }
        target.setRole(manager ? ROLE_MANAGER : ROLE_MEMBER);
        memberRepository.save(target);
        if (notificationService != null) {
            notificationService.notify(targetId, requesterId, "COMMUNITY_ROLE",
                    String.valueOf(communityId), manager
                            ? "đã bổ nhiệm bạn làm quản lý cộng đồng"
                            : "đã gỡ quyền quản lý cộng đồng của bạn");
        }
    }

    public List<PostResponse> pendingPosts(
            Long communityId, Long requesterId, int page, int size) {
        requireManager(communityId, requesterId);
        return postService.getCommunityPostsForReview(
                String.valueOf(communityId), requesterId, page, size);
    }

    public PostResponse decidePost(
            Long communityId, Long requesterId, String postId, boolean accept) {
        requireManager(communityId, requesterId);
        PostResponse response = postService.decideCommunityPost(
                postId, String.valueOf(communityId), requesterId,
                accept ? CommunityPostStatus.APPROVED : CommunityPostStatus.REJECTED);
        if (notificationService != null) {
            notificationService.notify(response.getUserId(), requesterId,
                    accept ? "COMMUNITY_POST_OK" : "COMMUNITY_POST_NO", postId,
                    accept ? "đã duyệt bài viết của bạn trong cộng đồng"
                            : "đã từ chối bài viết của bạn trong cộng đồng");
        }
        return response;
    }

    public void deletePost(Long communityId, Long requesterId, String postId) {
        requireManager(communityId, requesterId);
        postService.deletePostAsModerator(postId, requesterId, String.valueOf(communityId));
    }

    public void deleteComment(Long communityId, Long requesterId, String commentId) {
        requireManager(communityId, requesterId);
        if (commentService == null) throw new IllegalStateException("Comment service is unavailable");
        Comment comment = commentService.getCommentForCommunityManagement(commentId);
        requireCommunityPost(communityId, comment.getPostId());
        commentService.deleteCommentAsModerator(commentId, requesterId);
    }

    private Post requireCommunityPost(Long communityId, String postId) {
        Post post = postService.getPostForCommunityManagement(postId);
        if (!String.valueOf(communityId).equals(post.getCommunityId())) {
            throw new ResourceNotFoundException("Không tìm thấy bài viết trong cộng đồng này");
        }
        return post;
    }

    private void notifyManagersOfPendingPost(Long communityId, Long authorId, String postId) {
        if (notificationService == null) return;
        memberRepository.findByCommunityIdAndRoleInOrderByJoinedAtAsc(
                        communityId, managerRoles(), PageRequest.of(0, 100))
                .forEach(manager -> notificationService.notify(
                        manager.getUserId(), authorId, "COMMUNITY_REVIEW", postId,
                        "đã gửi một bài viết chờ duyệt trong cộng đồng"));
    }

    private List<CommunityMemberResponse> toMemberResponses(List<CommunityMember> memberships) {
        if (memberships.isEmpty()) return List.of();
        if (userRepository == null) throw new IllegalStateException("User repository is unavailable");
        Map<Long, User> users = userRepository.findAllById(
                        memberships.stream().map(CommunityMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, user -> user));
        return memberships.stream().map(member -> {
            User user = users.get(member.getUserId());
            return new CommunityMemberResponse(
                    member.getUserId(),
                    user == null ? null : user.getUsername(),
                    user == null ? null : user.getFullName(),
                    user == null ? AvatarUtil.resolve(null) : AvatarUtil.resolve(user.getAvatarUrl()),
                    normalizeRole(member.getRole()),
                    member.getJoinedAt());
        }).toList();
    }

    public List<CommunityMemberResponse> pendingMembers(Long communityId, Long requesterId) {
        return pendingMembers(communityId, requesterId, 0, 50);
    }

    public List<CommunityMemberResponse> pendingMembers(
            Long communityId, Long requesterId, int page, int size) {
        requireManager(communityId, requesterId);
        return toMemberResponses(memberRepository.findByCommunityIdAndRole(
                communityId, ROLE_PENDING, PageRequest.of(page, size)));
    }

    @Transactional
    public void decideMember(Long communityId, Long requesterId, Long targetId, boolean accept) {
        requireManager(communityId, requesterId);
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
        if (notificationService != null) {
            notificationService.notify(targetId, requesterId,
                    accept ? "COMMUNITY_JOIN_OK" : "COMMUNITY_JOIN_NO",
                    String.valueOf(communityId),
                    accept ? "đã duyệt yêu cầu tham gia cộng đồng của bạn"
                            : "đã từ chối yêu cầu tham gia cộng đồng của bạn");
        }
    }

    @Transactional
    public void banMember(Long communityId, Long requesterId, Long targetId, boolean banned) {
        requireManager(communityId, requesterId);
        if (requesterId.equals(targetId)) throw new BadRequestException("Không thể tự cấm chính mình");
        CommunityMember member =
                memberRepository
                        .findByCommunityIdAndUserId(communityId, targetId)
                        .orElseGet(() -> new CommunityMember(communityId, targetId, ROLE_BANNED));
        if (isOwnerRole(member.getRole())) {
            throw new ForbiddenException("Không thể cấm chủ nhóm");
        }
        if (ROLE_MANAGER.equals(member.getRole()) && !isOwner(communityId, requesterId)) {
            throw new ForbiddenException("Chỉ chủ nhóm có thể cấm quản lý");
        }
        member.setRole(banned ? ROLE_BANNED : ROLE_MEMBER);
        memberRepository.save(member);
        refreshMemberCount(getCommunityOrThrow(communityId));
    }

    @Transactional
    public CommunityResponse updateCommunity(
            Long communityId, Long requesterId, CreateCommunityRequest request) {
        requireOwner(communityId, requesterId);
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
        return toResponse(saved, roleOf(communityId, requesterId));
    }

    @Transactional
    public void deleteCommunity(Long communityId, Long requesterId) {
        requireOwner(communityId, requesterId);
        Community community = getCommunityOrThrow(communityId);
        String avatarUrl = community.getAvatarUrl();
        String coverUrl = community.getCoverUrl();
        enqueueCleanupOrUseCompatibilityFallback(communityId, avatarUrl, coverUrl);
        memberRepository.deleteByCommunityId(communityId);
        communityRepository.delete(community);
    }

    private void enqueueCleanupOrUseCompatibilityFallback(
            Long communityId, String avatarUrl, String coverUrl) {
        if (cleanupOutbox != null) {
            cleanupOutbox.enqueue(communityId, avatarUrl, coverUrl);
            return;
        }
        // Only compatibility constructors omit the durable outbox. Production wiring always
        // supplies it; keeping this callback avoids breaking isolated legacy unit tests.
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

    private CommunityMember requireManager(Long communityId, Long userId) {
        CommunityMember membership = memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new ForbiddenException("Bạn không phải quản lý cộng đồng"));
        if (!isManagerRole(membership.getRole())) {
            throw new ForbiddenException("Chỉ chủ nhóm hoặc quản lý được thực hiện thao tác này");
        }
        return membership;
    }

    private CommunityMember requireOwner(Long communityId, Long userId) {
        CommunityMember membership = memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .orElseThrow(() -> new ForbiddenException("Bạn không phải chủ nhóm"));
        if (!isOwnerRole(membership.getRole())) {
            throw new ForbiddenException("Chỉ chủ nhóm được thực hiện thao tác này");
        }
        return membership;
    }

    private boolean isOwner(Long communityId, Long userId) {
        return memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .map(CommunityMember::getRole).map(this::isOwnerRole).orElse(false);
    }

    private boolean hasOwner(Long communityId) {
        return memberRepository.existsByCommunityIdAndRole(communityId, ROLE_OWNER)
                || memberRepository.existsByCommunityIdAndRole(communityId, ROLE_ADMIN);
    }

    private String roleOf(Long communityId, Long userId) {
        return memberRepository.findByCommunityIdAndUserId(communityId, userId)
                .map(CommunityMember::getRole).orElse(null);
    }

    private boolean isManagerRole(String role) {
        return ROLE_OWNER.equals(role) || ROLE_MANAGER.equals(role) || ROLE_ADMIN.equals(role);
    }

    private boolean isOwnerRole(String role) {
        return ROLE_OWNER.equals(role) || ROLE_ADMIN.equals(role);
    }

    private String normalizeRole(String role) {
        return ROLE_ADMIN.equals(role) ? ROLE_OWNER : role;
    }

    private List<String> activeRoles() {
        return List.of(ROLE_OWNER, ROLE_MANAGER, ROLE_ADMIN, ROLE_MEMBER);
    }

    private List<String> managerRoles() {
        return List.of(ROLE_OWNER, ROLE_MANAGER, ROLE_ADMIN);
    }

    private boolean isActiveMember(Long communityId, Long userId) {
        return memberRepository.existsByCommunityIdAndUserIdAndRoleIn(
                communityId, userId, activeRoles());
    }

    private boolean isActiveMember(CommunityMember membership) {
        return membership != null && activeRoles().contains(membership.getRole());
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
                                community.getId(), activeRoles()));
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
                            return toResponse(c, role);
                        })
                .collect(Collectors.toList());
    }

    private CommunityResponse toResponse(Community community, String rawRole) {
        String role = normalizeRole(rawRole);
        boolean manager = isManagerRole(rawRole);
        boolean owner = isOwnerRole(rawRole);
        CommunityResponse dto = new CommunityResponse();
        dto.setId(community.getId());
        dto.setName(community.getName());
        dto.setDescription(community.getDescription());
        dto.setAvatarUrl(community.getAvatarUrl());
        dto.setCoverUrl(community.getCoverUrl());
        dto.setPrivacy(community.getPrivacy());
        dto.setMemberCount(community.getMemberCount());
        dto.setCreatedAt(community.getCreatedAt());
        dto.setMember(activeRoles().contains(rawRole));
        // Preserve the old JSON property for clients that already use `admin`.
        dto.setAdmin(manager);
        dto.setManager(manager);
        dto.setOwner(owner);
        dto.setRole(role);
        return dto;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
