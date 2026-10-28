package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.*;
import com.aloute.model.entity.*;
import com.aloute.repository.*;

import org.junit.jupiter.api.Test;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;

import java.util.*;
import java.util.stream.IntStream;

class ContentAccessBatchTest {
    private final PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final IFriendRepository friends = mock(IFriendRepository.class);
    private final ICommunityRepository communities = mock(ICommunityRepository.class);
    private final ICommunityMemberRepository members = mock(ICommunityMemberRepository.class);
    private final ContentAccessService service =
            new ContentAccessService(documentStore, users, friends, communities, members);

    @Test
    void publicBatchUsesTwoQueriesRegardlessOfPostCount() {
        var authors = IntStream.rangeClosed(1, 120).mapToObj(i -> author(i, false)).toList();
        var posts =
                IntStream.rangeClosed(1, 120)
                        .mapToObj(i -> post(i, PostVisibility.PUBLIC))
                        .toList();
        when(users.findAllById(any())).thenReturn(authors);
        var batch = service.forPosts(posts, 999L);
        assertEquals(120, batch.readable(posts).size());
        assertEquals(120, batch.readable(posts).size());
        verify(users, times(1)).findAllById(any());
        verify(documentStore, times(1)).find(any(Query.class), eq(UserBlock.class));
        verifyNoMoreInteractions(users, documentStore);
        verifyNoInteractions(friends);
    }

    @Test
    void relationshipsAndBlocksAreAppliedWithoutPerPostQueries() {
        when(users.findAllById(any()))
                .thenReturn(
                        List.of(
                                author(1, true),
                                author(2, false),
                                author(3, false),
                                author(4, false)));
        when(friends.findByFollowerIdAndFollowedIdIn(eq(99L), any()))
                .thenReturn(List.of(new Friend(99L, 1L), new Friend(99L, 2L)));
        when(friends.findByFollowedIdAndFollowerIdIn(eq(99L), any()))
                .thenReturn(List.of(new Friend(2L, 99L)));
        UserBlock block = new UserBlock();
        block.setOwnerId(3L);
        block.setTargetId(99L);
        when(documentStore.find(any(Query.class), eq(UserBlock.class))).thenReturn(List.of(block));
        var posts =
                List.of(
                        post(1, PostVisibility.PUBLIC),
                        post(2, PostVisibility.FRIENDS),
                        post(3, PostVisibility.PUBLIC),
                        post(4, PostVisibility.ONLY_ME));
        var batch = service.forPosts(posts, 99L);
        assertEquals(List.of(posts.get(0), posts.get(1)), batch.readable(posts));
        verify(friends, times(1)).findByFollowerIdAndFollowedIdIn(eq(99L), any());
        verify(friends, times(1)).findByFollowedIdAndFollowerIdIn(eq(99L), any());
        verifyNoMoreInteractions(friends);
    }

    @Test
    void ownRepostCannotBypassOriginalVisibility() {
        Post original = post(1, PostVisibility.ONLY_ME);
        original.setId("original");
        Post repost = post(2, PostVisibility.PUBLIC);
        repost.setRepostOfId("original");
        when(users.findAllById(any())).thenReturn(List.of(author(1, false), author(2, false)));
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of(original));
        assertFalse(service.forPosts(List.of(repost), 2L).canRead(repost));
        verify(documentStore, never()).findById(anyString(), eq(Post.class));
    }

    @Test
    void batchRulesMatchSingleRulesForVisibilityModerationAndOwnership() {
        for (PostVisibility visibility : PostVisibility.values()) {
            for (ModerationStatus moderation : ModerationStatus.values()) {
                for (Long viewer : Arrays.asList(null, 1L, 2L)) {
                    User author = author(1, false);
                    Post post = post(1, visibility);
                    post.setModerationStatus(moderation);
                    when(users.findById(1L)).thenReturn(Optional.of(author));
                    when(users.findAllById(any())).thenReturn(List.of(author));
                    assertEquals(
                            service.canRead(post, viewer),
                            service.forPosts(List.of(post), viewer).canRead(post));
                }
            }
        }
    }


    @Test
    void anonymousViewerCannotReadPrivateAccountOrFriendsOnlyPosts() {
        User privateAuthor = author(1, true);
        User publicAuthor = author(2, false);
        when(users.findAllById(any())).thenReturn(List.of(privateAuthor, publicAuthor));
        Post privatePost = post(1, PostVisibility.PUBLIC);
        Post friendsPost = post(2, PostVisibility.FRIENDS);

        var batch = service.forPosts(List.of(privatePost, friendsPost), null);

        assertTrue(batch.readable(List.of(privatePost, friendsPost)).isEmpty());
        verifyNoInteractions(friends);
    }

    @Test
    void publicRepostCannotExposeAPublicPostFromAPrivateAccountToStrangers() {
        Post original = post(1, PostVisibility.PUBLIC);
        original.setId("private-author-public-post");
        Post repost = post(2, PostVisibility.PUBLIC);
        repost.setRepostOfId("private-author-public-post");

        when(users.findAllById(any())).thenReturn(List.of(author(1, true), author(2, false)));
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of(original));

        var accessBatch = service.forPosts(List.of(repost), 99L);

        // The public visibility selected for the original post must not override the author's
        // private-account boundary when the post is rendered through somebody else's repost.
        assertFalse(accessBatch.canRead(repost));
        assertTrue(accessBatch.readable(List.of(repost)).isEmpty());
    }

    @Test
    void privateAccountContentRequiresAnAcceptedFollowEvenWhenThePostIsPublic() {
        User privateAuthor = author(1, true);
        when(users.findById(1L)).thenReturn(Optional.of(privateAuthor));

        assertFalse(service.canViewAccountContent(99L, 1L));
        when(friends.existsByFollowerIdAndFollowedId(99L, 1L)).thenReturn(true);
        assertTrue(service.canViewAccountContent(99L, 1L));
        assertTrue(service.canViewAccountContent(1L, 1L));
    }

    @Test
    void messagingPrivateAccountRequiresMutualAcceptedFollows() {
        when(users.findById(1L)).thenReturn(Optional.of(author(1, true)));

        assertFalse(service.canMessageAccount(99L, 1L));
        when(friends.existsByFollowerIdAndFollowedId(99L, 1L)).thenReturn(true);
        assertFalse(service.canMessageAccount(99L, 1L));
        when(friends.existsByFollowerIdAndFollowedId(1L, 99L)).thenReturn(true);
        assertTrue(service.canMessageAccount(99L, 1L));
    }

    @Test
    void inboxBatchHidesPrivateAccountsUnlessFollowIsMutual() {
        when(users.findAllById(any())).thenReturn(List.of(author(1, true), author(2, false)));
        when(friends.findByFollowerIdAndFollowedIdIn(eq(99L), any()))
                .thenReturn(List.of(new Friend(99L, 1L)));

        assertEquals(Set.of(2L), service.messageableProfileIds(99L, List.of(1L, 2L)));

        when(friends.findByFollowedIdAndFollowerIdIn(eq(99L), any()))
                .thenReturn(List.of(new Friend(1L, 99L)));
        assertEquals(Set.of(1L, 2L), service.messageableProfileIds(99L, List.of(1L, 2L)));
    }

    @Test
    void privateCommunityPostsAreHiddenFromNonMembersWithConstantQueries() {
        var authors = IntStream.rangeClosed(1, 50).mapToObj(i -> author(i, false)).toList();
        var posts = new ArrayList<Post>();
        IntStream.rangeClosed(1, 50)
                .forEach(
                        i -> {
                            Post p = post(i, PostVisibility.PUBLIC);
                            p.setCommunityId(i % 2 == 0 ? "10" : "20"); // 10 private, 20 public
                            posts.add(p);
                        });
        when(users.findAllById(any())).thenReturn(authors);
        when(communities.findByIdIn(any()))
                .thenReturn(List.of(community(10L, "PRIVATE"), community(20L, "PUBLIC")));
        when(members.findByUserIdAndCommunityIdIn(eq(99L), any())).thenReturn(List.of());

        var batch = service.forPosts(posts, 99L);

        assertEquals(25, batch.readable(posts).size());
        assertTrue(batch.readable(posts).stream().noneMatch(p -> "10".equals(p.getCommunityId())));
        verify(communities, times(1)).findByIdIn(any());
        verify(members, times(1)).findByUserIdAndCommunityIdIn(eq(99L), any());
    }

    @Test
    void privateCommunityPostsAreReadableByActiveMembersOnly() {
        Post p = post(1, PostVisibility.PUBLIC);
        p.setCommunityId("10");
        when(users.findAllById(any())).thenReturn(List.of(author(1, false)));
        when(communities.findByIdIn(any())).thenReturn(List.of(community(10L, "PRIVATE")));

        when(members.findByUserIdAndCommunityIdIn(eq(5L), any()))
                .thenReturn(List.of(new CommunityMember(10L, 5L, "MEMBER")));
        assertTrue(service.forPosts(List.of(p), 5L).canRead(p));

        when(members.findByUserIdAndCommunityIdIn(eq(6L), any()))
                .thenReturn(List.of(new CommunityMember(10L, 6L, "PENDING")));
        assertFalse(service.forPosts(List.of(p), 6L).canRead(p));
        assertFalse(service.forPosts(List.of(p), null).canRead(p));
        // even the author loses access once they are no longer an active member
        assertFalse(service.forPosts(List.of(p), 1L).canRead(p));
    }

    @Test
    void repostOfPrivateCommunityPostIsHidden() {
        Post original = post(1, PostVisibility.PUBLIC);
        original.setId("original");
        original.setCommunityId("10");
        Post repost = post(2, PostVisibility.PUBLIC);
        repost.setRepostOfId("original");
        when(users.findAllById(any())).thenReturn(List.of(author(1, false), author(2, false)));
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of(original));
        when(communities.findByIdIn(any())).thenReturn(List.of(community(10L, "PRIVATE")));
        when(members.findByUserIdAndCommunityIdIn(eq(7L), any())).thenReturn(List.of());
        assertFalse(service.forPosts(List.of(repost), 7L).canRead(repost));
    }

    @Test
    void batchAndSingleAgreeOnCommunityRules() {
        for (String privacy : List.of("PUBLIC", "PRIVATE")) {
            for (String role : Arrays.asList(null, "ADMIN", "MEMBER", "PENDING", "BANNED")) {
                for (Long viewer : Arrays.asList(null, 7L)) {
                    User a = author(1, false);
                    Post p = post(1, PostVisibility.PUBLIC);
                    p.setCommunityId("10");
                    when(users.findById(1L)).thenReturn(Optional.of(a));
                    when(users.findAllById(any())).thenReturn(List.of(a));
                    when(communities.findByIdIn(any())).thenReturn(List.of(community(10L, privacy)));
                    when(members.findByUserIdAndCommunityIdIn(any(), any()))
                            .thenReturn(
                                    role == null
                                            ? List.of()
                                            : List.of(new CommunityMember(10L, 7L, role)));
                    assertEquals(
                            service.canRead(p, viewer), service.forPosts(List.of(p), viewer).canRead(p));
                }
            }
        }
    }

    @Test
    void missingCommunityFailsClosed() {
        Post p = post(1, PostVisibility.PUBLIC);
        p.setCommunityId("404");
        when(users.findAllById(any())).thenReturn(List.of(author(1, false)));
        when(communities.findByIdIn(any())).thenReturn(List.of());
        assertFalse(service.forPosts(List.of(p), 5L).canRead(p));
    }

    private Community community(long id, String privacy) {
        Community c = new Community();
        c.setId(id);
        c.setPrivacy(privacy);
        return c;
    }

    private User author(long id, boolean privateAccount) {
        User user = new User();
        user.setId(id);
        user.setPrivateAccount(privateAccount);
        return user;
    }

    private Post post(long author, PostVisibility visibility) {
        Post post = new Post();
        post.setId("post-" + author);
        post.setUserId(Long.toString(author));
        post.setVisibility(visibility);
        return post;
    }
}
