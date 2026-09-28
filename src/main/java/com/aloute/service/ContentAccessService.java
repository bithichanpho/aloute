package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.document.*;
import com.aloute.model.entity.User;
import com.aloute.repository.*;

import lombok.RequiredArgsConstructor;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContentAccessService {
    private final MongoTemplate mongo;
    private final IUserRepository users;
    private final IFriendRepository friends;

    public AccessBatch forPosts(Collection<Post> posts, Long viewer) {
        Set<String> originalIds =
                posts.stream()
                        .filter(Objects::nonNull)
                        .map(Post::getRepostOfId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        Map<String, Post> originals =
                originalIds.isEmpty()
                        ? Map.of()
                        : mongo
                                .find(
                                        Query.query(Criteria.where("_id").in(originalIds)),
                                        Post.class)
                                .stream()
                                .collect(Collectors.toMap(Post::getId, p -> p));
        Set<Long> authorIds = new HashSet<>();
        posts.stream()
                .filter(Objects::nonNull)
                .forEach(p -> authorIds.add(Long.valueOf(p.getUserId())));
        originals.values().forEach(p -> authorIds.add(Long.valueOf(p.getUserId())));
        List<User> authors = authorIds.isEmpty() ? List.of() : users.findAllById(authorIds);
        Map<Long, User> authorsById =
                authors.stream().collect(Collectors.toMap(User::getId, u -> u));
        Set<Long> outgoing = new HashSet<>();
        Set<Long> incoming = new HashSet<>();
        boolean needsRelationships =
                authors.stream().anyMatch(User::isPrivateAccount)
                        || posts.stream()
                                .filter(Objects::nonNull)
                                .anyMatch(p -> p.getVisibility() == PostVisibility.FRIENDS)
                        || originals.values().stream()
                                .anyMatch(p -> p.getVisibility() == PostVisibility.FRIENDS);
        if (viewer != null && needsRelationships && !authorIds.isEmpty()) {
            friends.findByFollowerIdAndFollowedIdIn(viewer, authorIds)
                    .forEach(f -> outgoing.add(f.getFollowedId()));
            friends.findByFollowedIdAndFollowerIdIn(viewer, authorIds)
                    .forEach(f -> incoming.add(f.getFollowerId()));
        }
        return new AccessBatch(
                viewer, authorsById, blockedIds(viewer, authorIds), outgoing, incoming, originals);
    }

    public Set<Long> visibleUserIds(Long viewer, Collection<User> authors) {
        Set<Long> ids =
                authors.stream()
                        .filter(User::isActive)
                        .map(User::getId)
                        .collect(Collectors.toSet());
        ids.removeAll(blockedIds(viewer, ids));
        return ids;
    }

    public Set<Long> visibleProfileIds(Long viewer, Collection<Long> ids) {
        return ids.isEmpty()
                ? Set.of()
                : visibleUserIds(viewer, users.findAllById(new HashSet<>(ids)));
    }

    private Set<Long> blockedIds(Long viewer, Collection<Long> candidates) {
        if (viewer == null || candidates.isEmpty()) return Set.of();
        Query query =
                Query.query(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("ownerId")
                                                .is(viewer)
                                                .and("targetId")
                                                .in(candidates),
                                        Criteria.where("targetId")
                                                .is(viewer)
                                                .and("ownerId")
                                                .in(candidates)));
        return mongo.find(query, UserBlock.class).stream()
                .map(b -> viewer.equals(b.getOwnerId()) ? b.getTargetId() : b.getOwnerId())
                .filter(id -> !viewer.equals(id))
                .collect(Collectors.toSet());
    }

    public record AccessBatch(
            Long viewer,
            Map<Long, User> users,
            Set<Long> blocked,
            Set<Long> following,
            Set<Long> followers,
            Map<String, Post> originals) {
        public List<Post> readable(Collection<Post> posts) {
            return posts.stream().filter(this::canRead).toList();
        }

        public boolean canRead(Post post) {
            if (post == null) return false;
            Long author = Long.valueOf(post.getUserId());
            User user = users.get(author);
            if (user == null || !user.isActive() || blocked.contains(author)) return false;
            if (post.getRepostOfId() != null) {
                Post original = originals.get(post.getRepostOfId());
                if (original == null || original.getRepostOfId() != null || !canRead(original))
                    return false;
            }
            if (Objects.equals(viewer, author)) return true;
            if (post.getModerationStatus() == ModerationStatus.HIDDEN) return false;
            if (user.isPrivateAccount() && !following.contains(author)) return false;
            if (post.getVisibility() == PostVisibility.ONLY_ME) return false;
            return post.getVisibility() != PostVisibility.FRIENDS
                    || (following.contains(author) && followers.contains(author));
        }
    }

    public boolean blocked(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) return false;
        return mongo.exists(
                Query.query(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("ownerId").is(a).and("targetId").is(b),
                                        Criteria.where("ownerId").is(b).and("targetId").is(a))),
                UserBlock.class);
    }

    public boolean profileVisible(Long viewer, Long author) {
        return users.findById(author).filter(User::isActive).isPresent()
                && !blocked(viewer, author);
    }

    public void requireProfile(Long viewer, Long author) {
        if (!profileVisible(viewer, author))
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
    }

    public boolean canRead(Post post, Long viewer) {
        if (post == null) return false;
        Long author = Long.valueOf(post.getUserId());
        User user = users.findById(author).orElse(null);
        if (user == null || !user.isActive() || blocked(viewer, author)) return false;
        if (post.getRepostOfId() != null) {
            Post original = mongo.findById(post.getRepostOfId(), Post.class);
            if (original == null || original.getRepostOfId() != null || !canRead(original, viewer))
                return false;
        }
        if (Objects.equals(viewer, author)) return true;
        if (post.getModerationStatus() == ModerationStatus.HIDDEN) return false;
        if (user.isPrivateAccount()
                && (viewer == null || !friends.existsByFollowerIdAndFollowedId(viewer, author)))
            return false;
        if (post.getVisibility() == PostVisibility.ONLY_ME) return false;
        if (post.getVisibility() == PostVisibility.FRIENDS
                && (viewer == null
                        || !friends.existsByFollowerIdAndFollowedId(viewer, author)
                        || !friends.existsByFollowerIdAndFollowedId(author, viewer))) return false;
        return true;
    }

    public Post requirePost(String id, Long viewer) {
        Post post = mongo.findById(id, Post.class);
        if (!canRead(post, viewer)) throw new ResourceNotFoundException("Không tìm thấy bài viết");
        return post;
    }
}
