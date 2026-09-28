package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.FollowRequest;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.SavedPost;
import com.aloute.model.document.Story;
import com.aloute.model.document.StoryReply;
import com.aloute.model.document.StoryReaction;
import com.aloute.model.document.StoryView;
import com.aloute.model.document.UserBlock;
import com.aloute.model.entity.Friend;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.IUserRepository;

import lombok.RequiredArgsConstructor;

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SocialFeatureService {
    private final MongoTemplate mongo;
    private final ContentAccessService access;
    private final IUserRepository users;
    private final IFriendRepository friends;
    private final ICloseFriendRepository closeFriends;
    private final PostService posts;
    private final ModerationService moderation;
    private final AuthRateLimitService limits;
    private final ChatService chatService;
    private final NotificationService notificationService;

    public record StoryCard(
            String id, Long userId, String username, String fullName, String avatarUrl,
            String content, String imageUrl, String videoUrl, Instant createdAt, Instant expiresAt,
            long viewCount, boolean viewed, boolean liked, boolean owned) {}

    public record StoryViewer(
            Long userId, String username, String fullName, String avatarUrl, Instant viewedAt) {}

    public void block(Long owner, Long target, boolean blocked) {
        if (owner.equals(target)) throw new BadRequestException("Không thể tự chặn chính mình");
        if (!users.existsById(target))
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
        String id = owner + ":" + target;
        if (!blocked) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), UserBlock.class);
            return;
        }
        UserBlock block = new UserBlock();
        block.setId(id);
        block.setOwnerId(owner);
        block.setTargetId(target);
        mongo.save(block);
        closeFriends.findByRequesterIdAndReceiverId(owner, target).ifPresent(closeFriends::delete);
        closeFriends.findByRequesterIdAndReceiverId(target, owner).ifPresent(closeFriends::delete);
        friends.findByFollowerIdAndFollowedId(owner, target).ifPresent(friends::delete);
        friends.findByFollowerIdAndFollowedId(target, owner).ifPresent(friends::delete);
        mongo.remove(
                Query.query(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("followerId")
                                                .is(owner)
                                                .and("targetId")
                                                .is(target),
                                        Criteria.where("followerId")
                                                .is(target)
                                                .and("targetId")
                                                .is(owner))),
                FollowRequest.class);
    }

    public List<UserBlock> blocks(Long user) {
        return mongo.find(
                Query.query(Criteria.where("ownerId").is(user)).limit(200), UserBlock.class);
    }

    public List<FollowRequest> requests(Long user) {
        return mongo.find(
                Query.query(Criteria.where("targetId").is(user)).limit(200), FollowRequest.class);
    }

    public void decideFollow(Long user, String id, boolean accept) {
        FollowRequest request = mongo.findById(id, FollowRequest.class);
        if (request == null || !user.equals(request.getTargetId()))
            throw new ResourceNotFoundException("Không tìm thấy yêu cầu");
        access.requireProfile(user, request.getFollowerId());
        if (accept && !friends.existsByFollowerIdAndFollowedId(request.getFollowerId(), user))
            friends.save(new Friend(request.getFollowerId(), user));
        mongo.remove(request);
    }

    public ContentReport report(Long user, String type, String id, String reason) {
        limits.checkAndRecord(
                "REPORT",
                user.toString(),
                20,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(30),
                "Thử lại sau %d phút",
                "Bạn đã gửi quá nhiều báo cáo");
        if (reason == null || reason.isBlank() || reason.length() > 1000)
            throw new BadRequestException("Lý do cần 1–1000 ký tự");
        switch (type) {
            case "POST" -> access.requirePost(id, user);
            case "COMMENT" -> {
                Comment comment = mongo.findById(id, Comment.class);
                if (comment == null)
                    throw new ResourceNotFoundException("Không tìm thấy bình luận");
                access.requirePost(comment.getPostId(), user);
            }
            case "USER" -> access.requireProfile(user, Long.valueOf(id));
            default -> throw new BadRequestException("Loại báo cáo không hợp lệ");
        }
        ContentReport report = new ContentReport();
        report.setId(user + ":" + type + ":" + id);
        ContentReport existing = mongo.findById(report.getId(), ContentReport.class);
        if (existing != null) return existing;
        report.setReporterId(user);
        report.setTargetType(type);
        report.setTargetId(id);
        report.setReason(reason.trim());
        return mongo.insert(report);
    }

    public void requireAdmin(Long user) {
        if (users.findById(user)
                .filter(
                        u ->
                                u.isActive()
                                        && u.getRole() != null
                                        && "ROLE_ADMIN".equals(u.getRole().getName()))
                .isEmpty())
            throw new ForbiddenException("Chỉ quản trị viên hệ thống được truy cập");
    }

    public Map<String, Object> queue(Long user) {
        requireAdmin(user);
        return Map.of(
                "reports",
                mongo.find(
                        Query.query(Criteria.where("status").is("OPEN")).limit(100),
                        ContentReport.class),
                "posts",
                mongo.find(
                        Query.query(Criteria.where("moderationStatus").is(ModerationStatus.FLAGGED))
                                .limit(100),
                        Post.class),
                "comments",
                mongo.find(
                        Query.query(Criteria.where("moderationStatus").is(ModerationStatus.FLAGGED))
                                .limit(100),
                        Comment.class));
    }

    public Object reviewContent(Long user, String type, String id) {
        requireAdmin(user);
        if (type.equals("POST")) return mongo.findById(id, Post.class);
        if (type.equals("COMMENT")) return mongo.findById(id, Comment.class);
        if (type.equals("USER"))
            return users.findById(Long.valueOf(id))
                    .map(
                            u ->
                                    Map.of(
                                            "username",
                                            u.getUsername(),
                                            "fullName",
                                            u.getFullName(),
                                            "active",
                                            u.isActive()))
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản"));
        throw new BadRequestException("Loại nội dung không hợp lệ");
    }

    public void moderate(Long user, String type, String id, String decision, String reportId) {
        requireAdmin(user);
        if (!Set.of("APPROVE", "HIDE", "DISMISS").contains(decision))
            throw new BadRequestException("Quyết định không hợp lệ");
        ContentReport report =
                reportId == null ? null : mongo.findById(reportId, ContentReport.class);
        if (reportId != null
                && (report == null
                        || !type.equals(report.getTargetType())
                        || !id.equals(report.getTargetId())))
            throw new BadRequestException("Báo cáo không khớp nội dung");
        if (!decision.equals("DISMISS")) {
            ModerationStatus status =
                    decision.equals("HIDE") ? ModerationStatus.HIDDEN : ModerationStatus.APPROVED;
            switch (type) {
                case "POST", "COMMENT" -> {
                    Class<?> entity = type.equals("POST") ? Post.class : Comment.class;
                    if (mongo.updateFirst(
                                            Query.query(Criteria.where("_id").is(id)),
                                            new Update().set("moderationStatus", status),
                                            entity)
                                    .getMatchedCount()
                            == 0) {
                        throw new ResourceNotFoundException("Không tìm thấy nội dung");
                    }
                }
                case "USER" -> {
                    var target =
                            users.findById(Long.valueOf(id))
                                    .orElseThrow(
                                            () ->
                                                    new ResourceNotFoundException(
                                                            "Không tìm thấy tài khoản"));
                    if (target.getId().equals(user)
                            || "ROLE_ADMIN".equals(target.getRole().getName())) {
                        throw new BadRequestException("Không thể khóa quản trị viên");
                    }
                    target.setActive(!decision.equals("HIDE"));
                    target.setSelfDeactivated(false);
                    target.setTokensRevokedAt(Instant.now());
                    users.save(target);
                }
                default -> throw new BadRequestException("Loại nội dung không hợp lệ");
            }
        }
        if (report == null) {
            report = new ContentReport();
            report.setReporterId(user);
            report.setTargetType(type);
            report.setTargetId(id);
            report.setReason("Kiểm duyệt chủ động");
        }
        report.setStatus("RESOLVED");
        report.setReviewedBy(user);
        report.setReviewedAt(Instant.now());
        report.setDecision(decision);
        mongo.save(report);
    }

    public void save(Long user, String postId, boolean saved) {
        String id = user + ":" + postId;
        if (!saved) {
            mongo.remove(Query.query(Criteria.where("_id").is(id)), SavedPost.class);
            return;
        }
        access.requirePost(postId, user);
        SavedPost item = new SavedPost();
        item.setId(id);
        item.setUserId(user);
        item.setPostId(postId);
        mongo.save(item);
    }

    public List<com.aloute.dto.response.PostResponse> saved(Long user, int page) {
        List<SavedPost> items =
                mongo.find(
                        Query.query(Criteria.where("userId").is(user))
                                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                                .skip(Math.max(0, page) * 20L)
                                .limit(20),
                        SavedPost.class);
        return posts.getPostsByIds(items.stream().map(SavedPost::getPostId).toList(), user);
    }

    public void edit(Long user, String id, com.aloute.dto.request.CreatePostRequest request) {
        Post post = access.requirePost(id, user);
        if (!post.getUserId().equals(user.toString()))
            throw new ForbiddenException("Chỉ tác giả được sửa bài");
        PostRevision revision = new PostRevision();
        revision.setPostId(id);
        revision.setContent(post.getContent());
        revision.setImageUrl(post.getImageUrl());
        revision.setVideoUrl(post.getVideoUrl());
        revision.setEditorId(user);
        revision.setVisibility(
                post.getVisibility() == null ? "PUBLIC" : post.getVisibility().name());
        var result = moderation.check(request.getContent());
        if (!moderation.isMediaApproved(request.getImageUrl(), "image"))
            throw new BadRequestException("Ảnh không vượt qua kiểm duyệt an toàn");
        if (!moderation.isMediaApproved(request.getVideoUrl(), "video"))
            throw new BadRequestException("Video không vượt qua kiểm duyệt an toàn");
        var updated =
                mongo.updateFirst(
                        Query.query(
                                Criteria.where("_id")
                                        .is(id)
                                        .and("updatedAt")
                                        .is(post.getUpdatedAt())
                                        .and("content")
                                        .is(post.getContent())),
                        new Update()
                                .set("content", request.getContent())
                                .set("imageUrl", request.getImageUrl())
                                .set("videoUrl", request.getVideoUrl())
                                .set("visibility", request.getVisibility())
                                .set(
                                        "hashtags",
                                        ContentTagService.extract(request.getContent(), '#'))
                                .set(
                                        "mentions",
                                        ContentTagService.extract(request.getContent(), '@'))
                                .set(
                                        "moderationStatus",
                                        moderation.resolveStatus(result.getScore()))
                                .set("toxicityScore", result.getScore())
                                .push("editHistory", revision)
                                .set("updatedAt", java.time.LocalDateTime.now()),
                        Post.class);
        if (updated.getMatchedCount() == 0)
            throw new BadRequestException("Bài viết vừa thay đổi. Hãy tải lại trước khi sửa.");
    }

    public List<PostRevision> history(Long user, String post) {
        Post target = access.requirePost(post, user);
        if (!target.getUserId().equals(user.toString()))
            throw new ForbiddenException("Chỉ tác giả được xem lịch sử");
        if (target.getEditHistory() == null) return List.of();
        return target.getEditHistory().stream()
                .sorted(Comparator.comparing(PostRevision::getCreatedAt).reversed())
                .limit(100)
                .toList();
    }

    public Story story(Long user, String content, String image, String video) {
        limits.checkAndRecord(
                "STORY",
                user.toString(),
                30,
                java.time.Duration.ofHours(1),
                java.time.Duration.ofMinutes(15),
                "Thử lại sau %d phút",
                "Bạn đã đăng quá nhiều tin");
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.isBlank()
                && (image == null || image.isBlank())
                && (video == null || video.isBlank()))
            throw new BadRequestException("Tin cần có nội dung, hình ảnh hoặc video");
        if (safeContent.length() > 2000)
            throw new BadRequestException("Nội dung không được vượt quá 2000 ký tự");
        if (!safeContent.isBlank()
                && moderation.resolveStatus(moderation.check(safeContent).getScore())
                != ModerationStatus.APPROVED)
            throw new BadRequestException("Tin không vượt qua kiểm duyệt");
        if (!moderation.isMediaApproved(image, "image")
                || !moderation.isMediaApproved(video, "video"))
            throw new BadRequestException("Ảnh hoặc video không vượt qua kiểm duyệt an toàn");
        Story story = new Story();
        story.setUserId(user);
        story.setContent(safeContent);
        story.setImageUrl(image);
        story.setVideoUrl(video);
        story.setExpiresAt(Instant.now().plusSeconds(86400));
        return mongo.insert(story);
    }

    public List<StoryCard> stories(Long user) {
        Set<Long> allowed = new java.util.HashSet<>();
        allowed.add(user);
        friends.findByFollowerId(user).forEach(friend -> allowed.add(friend.getFollowedId()));
        List<Story> stories = mongo.find(
                Query.query(Criteria.where("expiresAt").gt(Instant.now()).and("userId").in(allowed))
                        .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(100), Story.class);
        if (stories.isEmpty()) return List.of();
        Map<Long, com.aloute.model.entity.User> authors = new HashMap<>();
        users.findAllById(stories.stream().map(Story::getUserId).collect(java.util.stream.Collectors.toSet()))
                .forEach(author -> authors.put(author.getId(), author));
        List<String> ids = stories.stream().map(Story::getId).toList();
        Set<String> viewed = Set.copyOf(mongo.findDistinct(
                Query.query(Criteria.where("userId").is(user).and("storyId").in(ids)),
                "storyId", StoryView.class, String.class));
        Set<String> liked = Set.copyOf(mongo.findDistinct(
                Query.query(Criteria.where("userId").is(user).and("storyId").in(ids)),
                "storyId", StoryReaction.class, String.class));
        Map<String, Long> counts = storyViewCounts(ids);
        return stories.stream().filter(story -> authors.containsKey(story.getUserId())).map(story -> {
            var author = authors.get(story.getUserId());
            return new StoryCard(story.getId(), author.getId(), author.getUsername(), author.getFullName(),
                    author.getAvatarUrl(), story.getContent(), story.getImageUrl(), story.getVideoUrl(),
                    story.getCreatedAt(), story.getExpiresAt(), counts.getOrDefault(story.getId(), 0L),
                    viewed.contains(story.getId()), liked.contains(story.getId()), user.equals(story.getUserId()));
        }).toList();
    }

    private Map<String, Long> storyViewCounts(List<String> ids) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("storyId").in(ids)),
                Aggregation.group("storyId").count().as("total"));
        Map<String, Long> result = new HashMap<>();
        mongo.aggregate(aggregation, StoryView.class, Document.class).forEach(row ->
                result.put(row.getString("_id"), ((Number) row.get("total")).longValue()));
        return result;
    }

    public void deleteStory(Long user, String id) {
        mongo.remove(Query.query(Criteria.where("_id").is(id).and("userId").is(user)), Story.class);
    }

    public long viewStory(Long user, String id) {
        Story story = requireVisibleStory(user, id);
        if (!story.getUserId().equals(user)) {
            StoryView view = new StoryView();
            view.setId(id + ":" + user);
            view.setStoryId(id);
            view.setUserId(user);
            mongo.save(view);
        }
        return mongo.count(Query.query(Criteria.where("storyId").is(id)), StoryView.class);
    }

    public StoryReply replyStory(Long user, String id, String content) {
        Story story = requireVisibleStory(user, id);
        if (story.getUserId().equals(user)) {
            throw new BadRequestException("Không thể tự trả lời tin của chính mình");
        }
        if (content == null || content.isBlank() || content.length() > 1000) {
            throw new BadRequestException("Phản hồi cần 1–1000 ký tự");
        }
        if (moderation.resolveStatus(moderation.check(content).getScore())
                == ModerationStatus.HIDDEN) {
            throw new BadRequestException("Phản hồi không vượt qua kiểm duyệt");
        }
        StoryReply reply = new StoryReply();
        reply.setStoryId(id);
        reply.setSenderId(user);
        reply.setOwnerId(story.getUserId());
        reply.setContent(content.trim());
        StoryReply saved = mongo.insert(reply);
        chatService.sendStoryReply(
                user,
                story.getUserId(),
                content.trim(),
                id,
                story.getContent(),
                story.getImageUrl(),
                story.getVideoUrl());
        notificationService.notify(story.getUserId(), user, "STORY_REPLY", id, "đã trả lời tin của bạn");
        return saved;
    }

    public boolean reactStory(Long user, String id, boolean liked) {
        Story story = requireVisibleStory(user, id);
        String reactionId = id + ":" + user;
        if (!liked) {
            mongo.remove(Query.query(Criteria.where("_id").is(reactionId)), StoryReaction.class);
            return false;
        }
        StoryReaction reaction = new StoryReaction();
        reaction.setId(reactionId);
        reaction.setStoryId(id);
        reaction.setUserId(user);
        mongo.save(reaction);
        if (!story.getUserId().equals(user)) {
            notificationService.notify(story.getUserId(), user, "STORY_REACTION", id, "đã thả tim tin của bạn");
        }
        return true;
    }

    public List<StoryViewer> storyViewers(Long user, String id) {
        Story story = mongo.findById(id, Story.class);
        if (story == null) throw new ResourceNotFoundException("Không tìm thấy tin");
        if (!user.equals(story.getUserId()))
            throw new ForbiddenException("Chỉ chủ tin được xem danh sách người xem");
        List<StoryView> views = mongo.find(
                Query.query(Criteria.where("storyId").is(id))
                        .with(Sort.by(Sort.Direction.DESC, "viewedAt")).limit(500),
                StoryView.class);
        Map<Long, com.aloute.model.entity.User> viewers = new HashMap<>();
        users.findAllById(views.stream().map(StoryView::getUserId).collect(java.util.stream.Collectors.toSet()))
                .forEach(viewer -> viewers.put(viewer.getId(), viewer));
        return views.stream().filter(view -> viewers.containsKey(view.getUserId())).map(view -> {
            var viewer = viewers.get(view.getUserId());
            return new StoryViewer(viewer.getId(), viewer.getUsername(), viewer.getFullName(),
                    viewer.getAvatarUrl(), view.getViewedAt());
        }).toList();
    }

    private Story requireVisibleStory(Long viewer, String id) {
        Story story = mongo.findById(id, Story.class);
        if (story == null
                || story.getExpiresAt() == null
                || !story.getExpiresAt().isAfter(Instant.now())
                || !access.profileVisible(viewer, story.getUserId())) {
            throw new ResourceNotFoundException("Không tìm thấy tin");
        }
        return story;
    }
}
