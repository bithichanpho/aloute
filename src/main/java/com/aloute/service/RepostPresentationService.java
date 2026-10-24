package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.util.AvatarUtil;
import com.aloute.dto.response.PostResponse;
import com.aloute.dto.response.ReposterCursorPage;
import com.aloute.model.document.Post;
import com.aloute.model.document.Repost;
import com.aloute.model.entity.User;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;
import org.springframework.stereotype.Service;

/** Maps repost relationships into bounded actor previews and cursor pages. */
@Service
public class RepostPresentationService {
    public static final int PREVIEW_SIZE = 3;

    private final IRepostRepository repostRepository;
    private final IPostRepository postRepository;
    private final IUserRepository userRepository;
    private final PostgresDocumentStore documentStore;

    public RepostPresentationService(
            IRepostRepository repostRepository,
            IPostRepository postRepository,
            IUserRepository userRepository,
            PostgresDocumentStore documentStore) {
        this.repostRepository = repostRepository;
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.documentStore = documentStore;
    }

    /**
     * Enriches a feed page using the three reposter ids stored on each post. This keeps feed work
     * bounded to one PostgreSQL batch and one SQL batch regardless of the total number of reposts.
     */
    public void attachRecentReposters(List<PostResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        List<String> postIds = responses.stream()
                .map(PostResponse::getInteractionPostId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (postIds.isEmpty()) return;

        Map<String, Post> postsById = postRepository.findAllById(postIds).stream()
                .collect(Collectors.toMap(Post::getId, Function.identity()));
        Map<String, List<String>> previewIdsByPost = new java.util.HashMap<>();
        Set<Long> userIds = new LinkedHashSet<>();

        for (String postId : postIds) {
            Post post = postsById.get(postId);
            if (post == null) continue;
            List<String> previewIds = normalizedPreviewIds(post.getRecentReposterIds());
            // Existing records predate the denormalized field. Repair each legacy post once using
            // an index-backed, bounded query instead of loading its complete repost history.
            if (previewIds.isEmpty() && post.getRepostCount() > 0) {
                previewIds = recentReposterIds(postId);
                documentStore.updateFirst(
                        Query.query(Criteria.where("_id").is(postId)),
                        new Update().set("recentReposterIds", previewIds),
                        Post.class);
            }
            previewIdsByPost.put(postId, previewIds);
            previewIds.stream().map(this::parseUserId).filter(Objects::nonNull).forEach(userIds::add);
        }

        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        for (PostResponse response : responses) {
            List<PostResponse.RepostActor> actors = previewIdsByPost
                    .getOrDefault(response.getInteractionPostId(), List.of()).stream()
                    .map(id -> toRepostActor(id, null, users))
                    .filter(Objects::nonNull)
                    .toList();
            response.setRecentReposters(actors);
            response.setOtherReposters(List.of());
            response.setOtherReposterCount(response.getRepostCount() - actors.size());
        }
    }

    /** Returns only the compact preview used after a repost mutation. */
    public List<PostResponse.RepostActor> loadRecentRepostActors(String postId) {
        return mapActors(loadRecentReposts(postId));
    }

    public List<String> recentReposterIds(String postId) {
        return loadRecentReposts(postId).stream()
                .map(Repost::getUserId)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<PostResponse.RepostActor> loadActorsByIds(List<String> reposterIds) {
        List<String> ids = normalizedPreviewIds(reposterIds);
        if (ids.isEmpty()) return List.of();
        Set<Long> userIds = ids.stream()
                .map(this::parseUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> users = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return ids.stream()
                .map(id -> toRepostActor(id, null, users))
                .filter(Objects::nonNull)
                .toList();
    }

    public ReposterCursorPage loadRepostActorsPage(
            String postId, String cursor, int requestedSize, int totalCount) {
        int size = Math.max(1, Math.min(50, requestedSize));
        List<Criteria> clauses = new ArrayList<>();
        clauses.add(Criteria.where("postId").is(postId));
        CursorPoint point = decodeCursor(cursor);
        if (point != null) {
            clauses.add(new Criteria().orOperator(
                    Criteria.where("createdAt").lt(point.createdAt()),
                    new Criteria().andOperator(
                            Criteria.where("createdAt").is(point.createdAt()),
                            Criteria.where("_id").lt(point.id()))));
        }

        Query query = Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<Repost> loaded = documentStore.find(query, Repost.class);
        boolean hasMore = loaded.size() > size;
        List<Repost> page = hasMore ? loaded.subList(0, size) : loaded;
        String nextCursor =
                hasMore && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : "";
        return new ReposterCursorPage(mapActors(page), nextCursor, hasMore, totalCount);
    }

    private List<Repost> loadRecentReposts(String postId) {
        if (postId == null || postId.isBlank()) return Collections.emptyList();
        return repostRepository.findByPostIdOrderByCreatedAtDesc(
                postId, PageRequest.of(0, PREVIEW_SIZE));
    }

    private List<PostResponse.RepostActor> mapActors(List<Repost> reposts) {
        if (reposts == null || reposts.isEmpty()) return Collections.emptyList();
        Set<Long> ids = reposts.stream()
                .map(Repost::getUserId)
                .map(this::parseUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> users = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return reposts.stream()
                .map(repost -> toRepostActor(repost.getUserId(), repost.getCreatedAt(), users))
                .filter(Objects::nonNull)
                .toList();
    }

    private PostResponse.RepostActor toRepostActor(
            String rawUserId, LocalDateTime createdAt, Map<Long, User> users) {
        Long userId = parseUserId(rawUserId);
        User user = userId == null ? null : users.get(userId);
        if (user == null) return null;
        PostResponse.RepostActor actor = new PostResponse.RepostActor();
        actor.setUserId(userId);
        actor.setUsername(user.getUsername());
        actor.setUserAvatarUrl(AvatarUtil.resolve(user.getAvatarUrl()));
        actor.setCreatedAt(createdAt);
        return actor;
    }

    private List<String> normalizedPreviewIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().filter(Objects::nonNull).distinct().limit(PREVIEW_SIZE).toList();
    }

    private Long parseUserId(String value) {
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String encodeCursor(Repost repost) {
        if (repost.getCreatedAt() == null || repost.getId() == null) return "";
        String value = repost.getCreatedAt() + "|" + repost.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private CursorPoint decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String value =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = value.lastIndexOf('|');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException();
            }
            return new CursorPoint(
                    LocalDateTime.parse(value.substring(0, separator)),
                    value.substring(separator + 1));
        } catch (IllegalArgumentException failure) {
            throw new BadRequestException("Con trỏ danh sách đăng lại không hợp lệ");
        }
    }

    private record CursorPoint(LocalDateTime createdAt, String id) {}
}
