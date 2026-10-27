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
import com.aloute.social.api.FollowedAccounts;
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
     * Enriches a feed page with the reposters that are visible to {@code viewerId}: the viewer
     * themselves and the accounts the viewer follows. Strangers never appear in the banner, and
     * {@code otherReposterCount} equals the number of names the "N người khác" popover lists.
     */
    public void attachRecentReposters(Long viewerId, List<PostResponse> responses) {
        if (responses == null || responses.isEmpty()) return;
        Map<String, VisiblePreview> previews = new java.util.HashMap<>();
        if (viewerId != null) {
            for (PostResponse response : responses) {
                String postId = response.getInteractionPostId();
                if (postId == null || response.getRepostCount() <= 0 || previews.containsKey(postId)) continue;
                previews.put(postId, visiblePreview(viewerId, postId));
            }
        }
        for (PostResponse response : responses) {
            VisiblePreview preview = previews.get(response.getInteractionPostId());
            response.setRecentReposters(preview == null ? List.of() : preview.actors());
            response.setOtherReposters(List.of());
            response.setOtherReposterCount(preview == null ? 0 : preview.otherCount());
        }
    }

    /** Compact preview used by feed cards and after a repost mutation. */
    public VisiblePreview visiblePreview(Long viewerId, String postId) {
        if (viewerId == null || postId == null || postId.isBlank()) return new VisiblePreview(List.of(), 0);
        Criteria visible = visibleReposts(viewerId, postId);
        List<Repost> recent = documentStore.find(
                Query.query(visible)
                        .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                        .limit(PREVIEW_SIZE),
                Repost.class);
        List<PostResponse.RepostActor> actors = mapActors(recent);
        long visibleTotal = recent.size() < PREVIEW_SIZE
                ? recent.size()
                : documentStore.count(Query.query(visible), Repost.class);
        int others = (int) Math.max(0, Math.min(Integer.MAX_VALUE, visibleTotal - recent.size()));
        return new VisiblePreview(actors, others);
    }

    public record VisiblePreview(List<PostResponse.RepostActor> actors, int otherCount) {}

    public List<String> recentReposterIds(String postId) {
        return loadRecentReposts(postId).stream()
                .map(Repost::getUserId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Lists the visible reposters who are NOT already named in the banner (older than the first
     * {@link #PREVIEW_SIZE} visible reposts), so the popover length matches "N người khác".
     */
    public ReposterCursorPage loadRepostActorsPage(
            Long viewerId, String postId, String cursor, int requestedSize) {
        int size = Math.max(1, Math.min(50, requestedSize));
        if (viewerId == null) return new ReposterCursorPage(List.of(), "", false, 0);
        Criteria visible = visibleReposts(viewerId, postId);
        long visibleTotal = documentStore.count(Query.query(visible), Repost.class);
        int totalOthers = (int) Math.max(0, Math.min(Integer.MAX_VALUE, visibleTotal - PREVIEW_SIZE));
        if (totalOthers == 0) return new ReposterCursorPage(List.of(), "", false, 0);

        CursorPoint point = decodeCursor(cursor);
        if (point == null) {
            List<Repost> named = documentStore.find(
                    Query.query(visible)
                            .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                            .limit(PREVIEW_SIZE),
                    Repost.class);
            if (named.size() < PREVIEW_SIZE) return new ReposterCursorPage(List.of(), "", false, 0);
            Repost last = named.get(named.size() - 1);
            point = new CursorPoint(last.getCreatedAt(), last.getId());
        }
        List<Criteria> clauses = new ArrayList<>();
        clauses.add(visible);
        clauses.add(Criteria.keysetBefore("createdAt", point.createdAt(), "_id", point.id()));

        Query query = Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new)))
                .with(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("_id")))
                .limit(size + 1);
        List<Repost> loaded = documentStore.find(query, Repost.class);
        boolean hasMore = loaded.size() > size;
        List<Repost> page = hasMore ? loaded.subList(0, size) : loaded;
        String nextCursor =
                hasMore && !page.isEmpty() ? encodeCursor(page.get(page.size() - 1)) : "";
        return new ReposterCursorPage(mapActors(page), nextCursor, hasMore, totalOthers);
    }

    /** Reposts of {@code postId} made by the viewer or by accounts the viewer follows. */
    private Criteria visibleReposts(Long viewerId, String postId) {
        return new Criteria().andOperator(
                Criteria.where("postId").is(postId),
                Criteria.where("userId").inReadView(
                        FollowedAccounts.READ_VIEW, viewerId, String.valueOf(viewerId)));
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
