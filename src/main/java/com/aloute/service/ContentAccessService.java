package com.aloute.service;

import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.model.document.*;
import com.aloute.model.entity.Community;
import com.aloute.model.entity.CommunityMember;
import com.aloute.model.entity.User;
import com.aloute.repository.*;


import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ContentAccessService {
    private final PostgresDocumentStore documentStore;
    private final IUserRepository users;
    private final IFriendRepository friends;
    private final ICommunityRepository communities;
    private final ICommunityMemberRepository communityMembers;
    private final com.aloute.social.api.CloseFriendships closeFriendships;

    @org.springframework.beans.factory.annotation.Autowired
    public ContentAccessService(
            PostgresDocumentStore documentStore,
            IUserRepository users,
            IFriendRepository friends,
            ICommunityRepository communities,
            ICommunityMemberRepository communityMembers,
            com.aloute.social.api.CloseFriendships closeFriendships) {
        this.documentStore = documentStore;
        this.users = users;
        this.friends = friends;
        this.communities = communities;
        this.communityMembers = communityMembers;
        this.closeFriendships = closeFriendships;
    }

    /** Compatibility construction cannot grant close-friend access without its public port. */
    public ContentAccessService(PostgresDocumentStore store, IUserRepository users,
            IFriendRepository friends, ICommunityRepository communities,
            ICommunityMemberRepository members) {
        this(store, users, friends, communities, members, (viewer, candidates) -> Set.of());
    }

    /** Basic profile labels remain visible; private activity needs a current approved follow. */
    public boolean profileContentVisible(Long viewer, Long authorId) {
        if (authorId == null) return false;
        return users.findById(authorId).filter(author -> profileVisible(viewer, author))
                .map(author -> authorId.equals(viewer) || !author.isPrivateAccount()
                        || (viewer != null && friends.existsByFollowerIdAndFollowedId(viewer, authorId)))
                .orElse(false);
    }

    /** Bounded lookup also distinguishes canceled/reissued generations used by the outbox. */
    public Set<String> pendingFollowRequestIds(Collection<String> references) {
        if (references == null || references.isEmpty()) return Set.of();
        var refs = references.stream().filter(Objects::nonNull).distinct().toList();
        Set<String> pending = new HashSet<>();
        for (int start = 0; start < refs.size(); start += 100) {
            var chunk = refs.subList(start, Math.min(start + 100, refs.size()));
            var ids = chunk.stream().map(ref -> ref.split("\\|", 2)[0]).distinct().toList();
            var requests = documentStore.find(Query.query(Criteria.where("_id").in(ids))
                    .limit(ids.size()), FollowRequest.class).stream()
                    .filter(r -> r.getFollowerId() != null && r.getTargetId() != null
                            && (r.getFollowerId() + ":" + r.getTargetId()).equals(r.getId())).toList();
            if (requests.isEmpty()) continue;
            var accepted = friends.findByFollowerIdInAndFollowedIdIn(
                            requests.stream().map(FollowRequest::getFollowerId).distinct().toList(),
                            requests.stream().map(FollowRequest::getTargetId).distinct().toList()).stream()
                    .map(f -> f.getFollowerId() + ":" + f.getFollowedId()).collect(Collectors.toSet());
            for (var request : requests) {
                if (accepted.contains(request.getId())) continue;
                for (String ref : chunk) {
                    if (ref.equals(request.getId()) || (request.getCreatedAt() != null
                            && ref.equals(request.getId() + "|" + request.getCreatedAt()))) pending.add(ref);
                }
            }
        }
        return Set.copyOf(pending);
    }

    private static final String PRIVACY_PRIVATE = "PRIVATE";
    private static final Set<String> ACTIVE_ROLES = Set.of("OWNER", "MANAGER", "ADMIN", "MEMBER");

    private static Long communityKey(String communityId) {
        if (communityId == null || communityId.isBlank()) return null;
        try {
            return Long.valueOf(communityId.trim());
        } catch (NumberFormatException e) {
            return -1L; // malformed id: never matches a community, fails closed
        }
    }

    /**
     * Returns the subset of community ids (as stored on Post.communityId) the viewer must NOT read.
     * A community is denied when it is missing, or PRIVATE and the viewer is not an ADMIN/MEMBER.
     * Always at most 2 queries (communities + memberships) regardless of how many posts.
     */
    private Set<String> deniedCommunityIds(Long viewer, Collection<String> rawIds) {
        Map<String, Long> keys = new HashMap<>();
        for (String raw : rawIds) {
            Long k = communityKey(raw);
            if (k != null) keys.put(raw, k);
        }
        if (keys.isEmpty()) return Set.of();
        List<Long> ids = keys.values().stream().distinct().toList();
        Map<Long, Community> found =
                communities.findByIdIn(ids).stream()
                        .collect(Collectors.toMap(Community::getId, c -> c, (a, b) -> a));
        Set<Long> memberOf = Set.of();
        boolean anyPrivate =
                found.values().stream().anyMatch(c -> PRIVACY_PRIVATE.equals(c.getPrivacy()));
        if (viewer != null && anyPrivate) {
            memberOf =
                    communityMembers.findByUserIdAndCommunityIdIn(viewer, ids).stream()
                            .filter(m -> ACTIVE_ROLES.contains(m.getRole()))
                            .map(CommunityMember::getCommunityId)
                            .collect(Collectors.toSet());
        }
        Set<String> denied = new HashSet<>();
        for (Map.Entry<String, Long> e : keys.entrySet()) {
            Community c = found.get(e.getValue());
            if (c == null
                    || (PRIVACY_PRIVATE.equals(c.getPrivacy()) && !memberOf.contains(e.getValue())))
                denied.add(e.getKey());
        }
        return denied;
    }

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
                        : documentStore
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
                                .anyMatch(p -> p.getVisibility() == PostVisibility.FRIENDS
                                        || p.getVisibility() == PostVisibility.CLOSE_FRIENDS)
                        || originals.values().stream()
                                .anyMatch(p -> p.getVisibility() == PostVisibility.FRIENDS
                                        || p.getVisibility() == PostVisibility.CLOSE_FRIENDS);
        if (viewer != null && needsRelationships && !authorIds.isEmpty()) {
            friends.findByFollowerIdAndFollowedIdIn(viewer, authorIds)
                    .forEach(f -> outgoing.add(f.getFollowedId()));
            friends.findByFollowedIdAndFollowerIdIn(viewer, authorIds)
                    .forEach(f -> incoming.add(f.getFollowerId()));
        }
        Set<Long> closeFriendAuthors = new HashSet<>();
        java.util.stream.Stream.concat(posts.stream(), originals.values().stream())
                .filter(Objects::nonNull)
                .filter(p -> p.getVisibility() == PostVisibility.CLOSE_FRIENDS)
                .map(p -> Long.valueOf(p.getUserId())).filter(id -> !id.equals(viewer))
                .forEach(closeFriendAuthors::add);
        Set<Long> acceptedCloseFriends = viewer == null || closeFriendAuthors.isEmpty()
                ? Set.of() : closeFriendships.acceptedAmong(viewer, closeFriendAuthors);
        Set<String> communityIds = new HashSet<>();
        posts.stream()
                .filter(Objects::nonNull)
                .map(Post::getCommunityId)
                .filter(Objects::nonNull)
                .forEach(communityIds::add);
        originals.values().stream()
                .map(Post::getCommunityId)
                .filter(Objects::nonNull)
                .forEach(communityIds::add);
        return new AccessBatch(
                viewer,
                authorsById,
                blockedIds(viewer, authorIds),
                outgoing,
                incoming,
                originals,
                deniedCommunityIds(viewer, communityIds), acceptedCloseFriends);
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
        return documentStore.find(query, UserBlock.class).stream()
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
            Map<String, Post> originals,
            Set<String> deniedCommunities,
            Set<Long> closeFriends) {
        public AccessBatch(Long viewer, Map<Long, User> users, Set<Long> blocked,
                Set<Long> following, Set<Long> followers, Map<String, Post> originals,
                Set<String> deniedCommunities) {
            this(viewer, users, blocked, following, followers, originals, deniedCommunities, Set.of());
        }
        public List<Post> readable(Collection<Post> posts) {
            return posts.stream().filter(this::canRead).toList();
        }

        public boolean canRead(Post post) {
            if (post == null) return false;
            Long author = Long.valueOf(post.getUserId());
            User user = users.get(author);
            if (user == null || !user.isActive() || blocked.contains(author)) return false;
            if (post.getCommunityId() != null && deniedCommunities.contains(post.getCommunityId()))
                return false;
            if (post.getRepostOfId() != null) {
                Post original = originals.get(post.getRepostOfId());
                if (original == null || original.getRepostOfId() != null || !canRead(original))
                    return false;
            }
            if (Objects.equals(viewer, author)) return true;
            if (post.getCommunityPostStatus() != null
                    && post.getCommunityPostStatus()
                            != com.aloute.model.document.CommunityPostStatus.APPROVED) return false;
            if (post.getModerationStatus() == ModerationStatus.HIDDEN
                    || post.getModerationStatus() == ModerationStatus.PENDING
                    || post.getModerationStatus() == ModerationStatus.FLAGGED) return false;
            if (user.isPrivateAccount() && !following.contains(author)) return false;
            if (post.getVisibility() == PostVisibility.ONLY_ME) return false;
            if (post.getVisibility() == PostVisibility.CLOSE_FRIENDS)
                return following.contains(author) && followers.contains(author) && closeFriends.contains(author);
            return post.getVisibility() != PostVisibility.FRIENDS
                    || (following.contains(author) && followers.contains(author));
        }
    }

    public boolean blocked(Long a, Long b) {
        if (a == null || b == null || a.equals(b)) return false;
        return documentStore.exists(
                Query.query(
                        new Criteria()
                                .orOperator(
                                        Criteria.where("ownerId").is(a).and("targetId").is(b),
                                        Criteria.where("ownerId").is(b).and("targetId").is(a))),
                UserBlock.class);
    }

    public boolean profileVisible(Long viewer, Long author) {
        return users.findById(author).map(user -> profileVisible(viewer, user)).orElse(false);
    }

    public boolean profileVisible(Long viewer, User author) {
        return author != null && author.isActive() && !blocked(viewer, author.getId());
    }

    public void requireProfile(Long viewer, Long author) {
        if (!profileVisible(viewer, author))
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
    }

    /** Uses an already loaded entity so profile assembly does not query the same user twice. */
    public void requireProfile(Long viewer, User author) {
        if (!profileVisible(viewer, author))
            throw new ResourceNotFoundException("Không tìm thấy tài khoản");
    }

    public boolean canRead(Post post, Long viewer) {
        if (post == null) return false;
        Long author = Long.valueOf(post.getUserId());
        User user = users.findById(author).orElse(null);
        if (user == null || !user.isActive() || blocked(viewer, author)) return false;
        if (post.getCommunityId() != null
                && !deniedCommunityIds(viewer, List.of(post.getCommunityId())).isEmpty())
            return false;
        if (post.getRepostOfId() != null) {
            Post original = documentStore.findById(post.getRepostOfId(), Post.class);
            if (original == null || original.getRepostOfId() != null || !canRead(original, viewer))
                return false;
        }
        if (Objects.equals(viewer, author)) return true;
        if (post.getCommunityPostStatus() != null
                && post.getCommunityPostStatus()
                        != com.aloute.model.document.CommunityPostStatus.APPROVED) return false;
        if (post.getModerationStatus() == ModerationStatus.HIDDEN
                || post.getModerationStatus() == ModerationStatus.PENDING
                || post.getModerationStatus() == ModerationStatus.FLAGGED) return false;
        if (user.isPrivateAccount()
                && (viewer == null || !friends.existsByFollowerIdAndFollowedId(viewer, author)))
            return false;
        if (post.getVisibility() == PostVisibility.ONLY_ME) return false;
        if ((post.getVisibility() == PostVisibility.FRIENDS
                        || post.getVisibility() == PostVisibility.CLOSE_FRIENDS)
                && (viewer == null
                        || !friends.existsByFollowerIdAndFollowedId(viewer, author)
                        || !friends.existsByFollowerIdAndFollowedId(author, viewer))) return false;
        if (post.getVisibility() == PostVisibility.CLOSE_FRIENDS
                && !closeFriendships.acceptedAmong(viewer, List.of(author)).contains(author)) return false;
        return true;
    }

    public Post requirePost(String id, Long viewer) {
        Post post = documentStore.findById(id, Post.class);
        if (!canRead(post, viewer)) throw new ResourceNotFoundException("Không tìm thấy bài viết");
        return post;
    }
}
