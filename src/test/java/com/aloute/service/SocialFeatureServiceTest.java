package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.*;
import com.aloute.dto.request.CreatePostRequest;
import com.aloute.model.document.*;
import com.aloute.model.entity.*;
import com.aloute.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.*;

class SocialFeatureServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final IFriendRepository friends = mock(IFriendRepository.class);
    private final ICloseFriendRepository closeFriends = mock(ICloseFriendRepository.class);
    private final SocialFeatureService service =
            new SocialFeatureService(
                    mongo,
                    access,
                    users,
                    friends,
                    closeFriends,
                    mock(PostService.class),
                    mock(ModerationService.class),
                    mock(AuthRateLimitService.class),
                    mock(NotificationService.class));

    @Test
    void ordinaryUserCannotViewModerationQueue() {
        User user = new User();
        user.setId(1L);
        user.setRole(new Role("ROLE_USER"));
        when(users.findById(1L)).thenReturn(Optional.of(user));
        assertThrows(ForbiddenException.class, () -> service.queue(1L));
        verifyNoInteractions(mongo);
    }

    @Test
    void blockRemovesBothFollowDirections() {
        when(users.existsById(2L)).thenReturn(true);
        Friend outgoing = new Friend(1L, 2L), incoming = new Friend(2L, 1L);
        when(friends.findByFollowerIdAndFollowedId(1L, 2L)).thenReturn(Optional.of(outgoing));
        when(friends.findByFollowerIdAndFollowedId(2L, 1L)).thenReturn(Optional.of(incoming));
        service.block(1L, 2L, true);
        verify(friends).delete(outgoing);
        verify(friends).delete(incoming);
        verify(mongo).save(argThat((UserBlock block) -> block.getId().equals("1:2")));
    }

    @Test
    void hiddenPostCannotBeEditedByAuthor() {
        Post hidden = new Post();
        hidden.setId("post-1");
        hidden.setUserId("1");
        hidden.setContent("violating");
        hidden.setModerationStatus(ModerationStatus.HIDDEN);
        when(access.requirePost("post-1", 1L)).thenReturn(hidden);
        CreatePostRequest request = new CreatePostRequest();
        request.setContent("clean now");

        assertThrows(ForbiddenException.class, () -> service.edit(1L, "post-1", request));

        verify(mongo, never()).updateFirst(any(), any(), eq(Post.class));
        assertEquals(ModerationStatus.HIDDEN, hidden.getModerationStatus());
    }

    @Test
    void cannotBlockSelf() {
        assertThrows(BadRequestException.class, () -> service.block(1L, 1L, true));
    }

    @Test
    void followRequestCanOnlyBeDecidedByRecipient() {
        FollowRequest request = new FollowRequest();
        request.setTargetId(2L);
        request.setFollowerId(1L);
        when(mongo.findById("request", FollowRequest.class)).thenReturn(request);
        assertThrows(
                ResourceNotFoundException.class, () -> service.decideFollow(3L, "request", true));
        verifyNoInteractions(friends);
    }


    @Test
    void declineFollowRequestRemovesMongoRequestWithoutWritingFriendEdge() {
        FollowRequest request = new FollowRequest();
        request.setId("request-1");
        request.setTargetId(2L);
        request.setFollowerId(1L);
        when(mongo.findById("request-1", FollowRequest.class)).thenReturn(request);

        service.decideFollow(2L, "request-1", false);

        verify(mongo).remove(any(org.springframework.data.mongodb.core.query.Query.class),
                eq(FollowRequest.class));
        verifyNoInteractions(friends);
    }


    @Test
    void cannotEditOrReadHistoryOfSomeoneElsesPost() {
        Post post = new Post();
        post.setUserId("2");
        when(access.requirePost("post", 1L)).thenReturn(post);
        assertThrows(
                ForbiddenException.class,
                () -> service.edit(1L, "post", new CreatePostRequest()));
        assertThrows(ForbiddenException.class, () -> service.history(1L, "post"));
        verifyNoInteractions(mongo);
    }

    @Test
    void hiddenOrBlockedPostCannotBeSaved() {
        doThrow(new ResourceNotFoundException("hidden")).when(access).requirePost("post", 1L);
        assertThrows(ResourceNotFoundException.class, () -> service.save(1L, "post", true));
        verifyNoInteractions(mongo);
    }


    @Test
    void adminModerationQueueUsesStablePagedQueriesAndIncludesHasMoreMetadata() {
        User admin = new User();
        admin.setId(9L);
        admin.setActive(true);
        admin.setRole(new Role("ROLE_ADMIN"));
        when(users.findById(9L)).thenReturn(Optional.of(admin));
        when(mongo.find(any(org.springframework.data.mongodb.core.query.Query.class), eq(ContentReport.class)))
                .thenReturn(List.of());
        when(mongo.find(any(org.springframework.data.mongodb.core.query.Query.class), eq(Post.class)))
                .thenReturn(List.of());
        when(mongo.find(any(org.springframework.data.mongodb.core.query.Query.class), eq(Comment.class)))
                .thenReturn(List.of());

        Map<String, Object> queue = service.queue(9L, 2, 15);

        assertEquals(2, queue.get("page"));
        assertEquals(15, queue.get("size"));
        assertEquals(false, queue.get("hasMore"));
        assertTrue(((List<?>) queue.get("reports")).isEmpty());
        var queries = org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongo).find(queries.capture(), eq(ContentReport.class));
        assertTrue(queries.getAllValues().stream().allMatch(q -> q.getSkip() == 30 && q.getLimit() == 16));
        assertTrue(queries.getAllValues().stream().allMatch(q -> !q.getSortObject().isEmpty()));
    }

    @Test
    void hostDoesNotInheritReportModerationPermission() {
        User host = new User();
        host.setId(10L);
        host.setActive(true);
        host.setRole(new Role("ROLE_HOST"));
        when(users.findById(10L)).thenReturn(Optional.of(host));
        assertThrows(ForbiddenException.class, () -> service.queue(10L));
        verifyNoInteractions(mongo);
    }

    @Test
    void duplicateReportDoesNotResetReviewDecision() {
        ContentReport reviewed = new ContentReport();
        reviewed.setStatus("RESOLVED");
        when(mongo.findById("1:POST:post", ContentReport.class)).thenReturn(reviewed);
        assertSame(reviewed, service.report(1L, "POST", "post", "Spam"));
        verify(mongo, never()).insert(any(ContentReport.class));
    }

    @Test
    void blocksUseStableCursorPaginationInsteadOfReturningAnArbitraryCappedList() {
        UserBlock newest = new UserBlock();
        newest.setId("1:3");
        newest.setOwnerId(1L);
        newest.setTargetId(3L);
        newest.setCreatedAt(Instant.parse("2026-10-19T10:00:00Z"));
        UserBlock older = new UserBlock();
        older.setId("1:2");
        older.setOwnerId(1L);
        older.setTargetId(2L);
        older.setCreatedAt(Instant.parse("2026-10-19T09:00:00Z"));
        when(mongo.find(any(Query.class), eq(UserBlock.class)))
                .thenReturn(List.of(newest, older));

        var page = service.blocks(1L, null, 1);

        assertEquals(List.of(newest), page.items());
        assertTrue(page.hasMore());
        assertFalse(page.nextCursor().isBlank());
    }

    @Test
    void socialListsRejectMalformedCursor() {
        assertThrows(BadRequestException.class, () -> service.requests(1L, "broken", 20));
        verify(mongo, never()).find(any(Query.class), eq(FollowRequest.class));
    }

}
