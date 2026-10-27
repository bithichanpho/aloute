package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.model.document.*;
import com.aloute.model.entity.*;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Real privacy policy and post mapping, with persistence replaced by doubles. */
class PrivateProfileContentTest {
    @Test
    void outageRetryPublishesToApprovedFollowerOnlyAfterSuccessfulModeration() {
        var f = new Fixture(); f.follow(true);
        var post = Fixture.post("outage","1");
        post.setModerationStatus(ModerationStatus.FLAGGED);
        post.setModerationFailureReason("TEXT_SERVICE_UNAVAILABLE"); post.setModerationAttempts(5);
        when(f.store.find(any(Query.class),eq(Post.class))).thenReturn(List.of(post));
        when(f.store.findById("outage",Post.class)).thenReturn(post);
        assertTrue(f.service.getPostsByUser(1L,2L).isEmpty());
        var moderation = mock(ModerationService.class);
        when(moderation.isEnabled()).thenReturn(true);
        when(moderation.check("body")).thenReturn(new com.aloute.dto.response.ModerationResult("safe",0.0));
        when(moderation.resolveStatus(any())).thenReturn(ModerationStatus.APPROVED);
        when(moderation.isMediaApproved(any(),any())).thenReturn(true);
        when(f.store.updateFirst(any(),any(),eq(Post.class))).thenAnswer(call -> {
            for (var operation : ((Update)call.getArgument(1)).operations()) {
                if (operation.field().equals("moderationStatus")) post.setModerationStatus((ModerationStatus)operation.value());
            }
            return new UpdateResult(1,1);
        });
        var dispatch = mock(com.aloute.posts.api.PostReviewDispatch.class);
        var retries = new com.aloute.posts.application.PostModerationRetryService(f.store,
                f.access::requirePost,mock(com.aloute.identity.api.ActionRateLimit.class),moderation,dispatch);
        retries.retry(1L,"outage");
        assertEquals(ModerationStatus.PENDING,post.getModerationStatus());
        assertTrue(f.service.getPostsByUser(1L,2L).isEmpty());
        var worker = new PostModerationWorker(f.store,moderation,new ModerationRetryPolicy(5,30),
                mock(org.springframework.cache.CacheManager.class),f.users,f.access,
                mock(com.aloute.notifications.api.Notifications.class));
        worker.moderate("outage").join();
        assertEquals(ModerationStatus.APPROVED,post.getModerationStatus());
        assertEquals(1,f.service.getPostsByUser(1L,2L).size());
        assertEquals(1,f.service.getPostsByUserCursor(1L,2L,null,20).items().size());
        f.follow(false);
        assertTrue(f.service.getPostsByUser(1L,2L).isEmpty());
        assertTrue(f.service.getPostsByUserCursor(1L,2L,null,20).items().isEmpty());
        verify(dispatch).submit("outage");
    }

    @Test
    void approvedFollowerReadsPublicPostsButNotOnlyMeOrUnreviewedContent() {
        var f = new Fixture(); f.follow(true);
        var post = Fixture.post("audience", "1");
        when(f.store.find(any(Query.class), eq(Post.class))).thenReturn(List.of(post));
        for (var status : ModerationStatus.values()) {
            for (var visibility : PostVisibility.values()) {
                post.setModerationStatus(status); post.setVisibility(visibility);
                boolean readable = status == ModerationStatus.APPROVED && visibility == PostVisibility.PUBLIC;
                assertEquals(readable, f.access.canRead(post,2L), status+"/"+visibility);
                assertEquals(readable, !f.service.getPostsByUser(1L,2L).isEmpty());
                assertEquals(readable, !f.service.getPostsByUserCursor(1L,2L,null,20).items().isEmpty());
            }
        }
        post.setModerationStatus(ModerationStatus.APPROVED); post.setVisibility(PostVisibility.FRIENDS);
        when(f.friends.existsByFollowerIdAndFollowedId(1L,2L)).thenReturn(true);
        when(f.friends.findByFollowedIdAndFollowerIdIn(eq(2L),any())).thenReturn(List.of(new Friend(1L,2L)));
        assertTrue(f.access.canRead(post,2L));
        assertEquals(1, f.service.getPostsByUser(1L,2L).size());
        f.follow(false);
        assertFalse(f.access.canRead(post,2L));
        assertTrue(f.service.getPostsByUser(1L,2L).isEmpty());
    }

    @Test
    void outsidersAnonymousAndPendingFollowersCannotReadAnyPrivateProfileEndpoint() {
        var f = new Fixture();
        var request = new FollowRequest();
        request.setId("2:1"); request.setFollowerId(2L); request.setTargetId(1L);
        when(f.store.findById("2:1", FollowRequest.class)).thenReturn(request);
        for (Long viewer : Arrays.asList(2L, null)) assertHidden(f, viewer);
        verify(f.store, never()).find(any(Query.class), eq(Post.class));
        verify(f.store, never()).find(any(Query.class), eq(Repost.class));
        verifyNoInteractions(f.reposts);
    }

    @Test
    void ownerAndCurrentApprovedFollowerKeepTheirViewsAndKickRevokesAllFourReads() {
        var f = new Fixture();
        f.follow(true);
        for (Long viewer : List.of(1L, 2L)) assertVisible(f, viewer);
        f.follow(false);
        clearInvocations(f.store, f.reposts);
        assertHidden(f, 2L);
        verify(f.store, never()).find(any(Query.class), eq(Post.class));
        verify(f.store, never()).find(any(Query.class), eq(Repost.class));
        verifyNoInteractions(f.reposts);
    }

    @Test
    void publicProfilesRemainReadableAndOriginalPrivacyStillAppliesToReposts() {
        var f = new Fixture();
        f.owner.setPrivateAccount(false);
        assertVisible(f, null);
        assertVisible(f, 2L);
        f.originalAuthor.setPrivateAccount(true);
        assertTrue(f.service.getRepostsByUser(1L, 1L, 0, 20).isEmpty());
        assertTrue(f.service.getRepostsByUserCursor(1L, 1L, null, 20).items().isEmpty());
    }

    @Test
    void blockAndDeactivationRevokeAccessEvenForApprovedFollowers() {
        var f = new Fixture(); f.follow(true);
        when(f.store.exists(any(Query.class), eq(UserBlock.class))).thenReturn(true);
        assertHidden(f, 2L);
        when(f.store.exists(any(Query.class), eq(UserBlock.class))).thenReturn(false);
        f.owner.setActive(false);
        assertHidden(f, 2L);
    }

    @Test
    void restUsesAuthenticatedViewerRatherThanClientSuppliedOwnerId() throws Exception {
        var f = new Fixture();
        var controller = new com.aloute.controller.PostController(f.service, mock(LikeService.class),
                mock(FriendService.class), mock(AuthRateLimitService.class), mock(ChatService.class),
                f.access, "http://localhost:8081");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        User user = user(2L, false); user.setRole(new Role("ROLE_USER"));
        var principal = new com.aloute.security.AloutePrincipal(user);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/posts/user/1")
                        .principal(authentication).param("currentUserId", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data").isEmpty());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/posts/user/1/cursor")
                        .principal(authentication).param("currentUserId", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.items").isEmpty());
    }

    private void assertHidden(Fixture f, Long viewer) {
        assertTrue(f.service.getPostsByUser(1L, viewer, 0, 20).isEmpty());
        var posts = f.service.getPostsByUserCursor(1L, viewer, null, 20);
        assertTrue(posts.items().isEmpty()); assertFalse(posts.hasMore());
        assertTrue(f.service.getRepostsByUser(1L, viewer, 0, 20).isEmpty());
        var reposts = f.service.getRepostsByUserCursor(1L, viewer, null, 20);
        assertTrue(reposts.items().isEmpty()); assertFalse(reposts.hasMore());
    }

    @Test
    void removeFollowerRevokesAllPrivateReadsAndRequiresFreshApproval() {
        var f = new Fixture(); f.follow(true); assertVisible(f, 2L);
        when(f.friends.removeFollower(1L, 2L)).thenAnswer(call -> { f.follow(false); return 1; });
        var notifications = mock(com.aloute.notifications.api.Notifications.class);
        var social = new FriendService(f.friends, notifications, f.access, f.store, f.users);
        assertTrue(social.removeFollower(1L, 2L).removed());
        assertHidden(f, 2L); assertVisible(f, 1L);
        assertFalse(social.toggleFollow(2L, 1L));
        verify(f.store).save(any(FollowRequest.class));
        verify(f.friends, never()).save(any(Friend.class));
        assertHidden(f, 2L);
    }

    private void assertVisible(Fixture f, Long viewer) {
        assertEquals(1, f.service.getPostsByUser(1L, viewer, 0, 20).size());
        assertEquals(1, f.service.getPostsByUserCursor(1L, viewer, null, 20).items().size());
        assertEquals(1, f.service.getRepostsByUser(1L, viewer, 0, 20).size());
        assertEquals(1, f.service.getRepostsByUserCursor(1L, viewer, null, 20).items().size());
    }

    private static User user(Long id, boolean privateAccount) {
        var user = new User(); user.setId(id); user.setUsername("user" + id);
        user.setPrivateAccount(privateAccount); user.setActive(true); return user;
    }

    private static class Fixture {
        final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
        final IUserRepository users = mock(IUserRepository.class);
        final IFriendRepository friends = mock(IFriendRepository.class);
        final IPostRepository posts = mock(IPostRepository.class);
        final IRepostRepository reposts = mock(IRepostRepository.class);
        final User owner = user(1L, true), originalAuthor = user(3L, false);
        final ContentAccessService access = new ContentAccessService(store, users, friends,
                mock(ICommunityRepository.class), mock(ICommunityMemberRepository.class));
        final PostService service;
        Fixture() {
            var own = post("private-post", "1"); var original = post("original", "3");
            var repost = new Repost(); repost.setId("1:original"); repost.setUserId("1");
            repost.setPostId("original"); repost.setCreatedAt(original.getCreatedAt());
            when(users.findById(1L)).thenReturn(Optional.of(owner));
            when(users.findById(3L)).thenReturn(Optional.of(originalAuthor));
            when(users.findAllById(any())).thenAnswer(call -> {
                var ids = new HashSet<Long>(); ((Iterable<Long>)call.getArgument(0)).forEach(ids::add);
                return List.of(owner, originalAuthor).stream().filter(u -> ids.contains(u.getId())).toList();
            });
            when(store.find(any(Query.class), eq(Post.class))).thenReturn(List.of(own));
            when(store.find(any(Query.class), eq(Repost.class))).thenReturn(List.of(repost));
            when(posts.findAllById(any())).thenReturn(List.of(original));
            when(reposts.findByUserIdOrderByCreatedAtDesc(eq("1"), any())).thenReturn(List.of(repost));
            var likes = mock(ILikeRepository.class); var presentation = mock(RepostPresentationService.class);
            var mapper = new PostResponseMapper(posts, users, likes, reposts, access, presentation);
            service = new PostService(posts, users, likes, mock(ModerationService.class),
                    mock(NotificationService.class), store, mock(ICommentRepository.class),
                    mock(INotificationRepository.class), reposts, access, presentation, mapper,
                    mock(PostModerationCoordinator.class), mock(MediaAssetLifecycleService.class));
        }
        void follow(boolean followed) {
            when(friends.existsByFollowerIdAndFollowedId(2L, 1L)).thenReturn(followed);
            when(friends.findByFollowerIdAndFollowedIdIn(eq(2L), any()))
                    .thenReturn(followed ? List.of(new Friend(2L, 1L)) : List.of());
        }
        static Post post(String id, String user) {
            var post = new Post(); post.setId(id); post.setUserId(user); post.setContent("body");
            post.setCreatedAt(LocalDateTime.of(2026,10,25,10,0)); return post;
        }
    }
}
