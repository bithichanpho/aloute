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
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;

import java.time.Instant;
import java.util.*;

class SocialFeatureServiceTest {
    @Test
    void acceptedRequestQueuesOneNoticeAndRemovesRequestInTheSameUseCase() {
        var request = new FollowRequest(); request.setId("6:1"); request.setFollowerId(6L); request.setTargetId(1L);
        when(documentStore.findById("6:1", FollowRequest.class)).thenReturn(request);
        service.decideFollow(1L, "6:1", true);
        verify(notifications).notifyFollowRequestAccepted(6L, 1L);
        verify(documentStore).remove(any(Query.class), eq(FollowRequest.class));
        when(friends.existsByFollowerIdAndFollowedId(6L, 1L)).thenReturn(true);
        service.decideFollow(1L, "6:1", true);
        verify(notifications, times(1)).notifyFollowRequestAccepted(6L, 1L);
    }
    private final PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final IFriendRepository friends = mock(IFriendRepository.class);
    private final ICloseFriendRepository closeFriends = mock(ICloseFriendRepository.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final SocialFeatureService service =
            new SocialFeatureService(
                    documentStore,
                    access,
                    users,
                    friends,
                    closeFriends,
                    mock(PostService.class),
                    mock(ModerationService.class),
                    mock(AuthRateLimitService.class),
                    notifications);

    @Test
    void requestCursorBatchesLabelsOnlyForTheReturnedPage() {
        var request=new FollowRequest(); request.setId("6:1"); request.setFollowerId(6L); request.setTargetId(1L);
        var extra=new FollowRequest(); extra.setId("7:1"); extra.setFollowerId(7L); extra.setTargetId(1L);
        when(documentStore.find(any(Query.class),eq(FollowRequest.class))).thenReturn(List.of(request,extra));
        var label=mock(IUserRepository.ProfileLabelEntry.class); when(label.getId()).thenReturn(6L);
        when(label.getUsername()).thenReturn("minhan"); when(label.getFullName()).thenReturn("Minh An"); when(label.getAvatarUrl()).thenReturn("/minhan.png");
        when(users.findProfileLabels(List.of(6L))).thenReturn(List.of(label));
        var page=service.requests(1L,null,1);
        assertTrue(page.hasMore()); assertFalse(page.nextCursor().isEmpty()); assertEquals(1,page.items().size());
        assertEquals("Minh An",page.items().getFirst().followerFullName()); assertEquals("minhan",page.items().getFirst().followerUsername());
        assertEquals("/minhan.png",page.items().getFirst().followerAvatarUrl()); verify(users).findProfileLabels(List.of(6L)); verifyNoMoreInteractions(users);
    }

    @Test
    void ordinaryUserCannotViewModerationQueue() {
        User user = new User();
        user.setId(1L);
        user.setRole(new Role("ROLE_USER"));
        when(users.findById(1L)).thenReturn(Optional.of(user));
        assertThrows(ForbiddenException.class, () -> service.queue(1L));
        verifyNoInteractions(documentStore);
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
        verify(documentStore).save(argThat((UserBlock block) -> block.getId().equals("1:2")));
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

        verify(documentStore, never()).updateFirst(any(), any(), eq(Post.class));
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
        when(documentStore.findById("request", FollowRequest.class)).thenReturn(request);
        assertThrows(
                ResourceNotFoundException.class, () -> service.decideFollow(3L, "request", true));
        verifyNoInteractions(friends);
    }


    @Test
    void declineFollowRequestRemovesPostgresRequestWithoutWritingFriendEdge() {
        FollowRequest request = new FollowRequest();
        request.setId("request-1");
        request.setTargetId(2L);
        request.setFollowerId(1L);
        when(documentStore.findById("request-1", FollowRequest.class)).thenReturn(request);

        service.decideFollow(2L, "request-1", false);

        verify(documentStore).remove(any(com.aloute.persistence.postgres.document.Query.class),
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
        verifyNoInteractions(documentStore);
    }

    @Test
    void hiddenOrBlockedPostCannotBeSaved() {
        doThrow(new ResourceNotFoundException("hidden")).when(access).requirePost("post", 1L);
        assertThrows(ResourceNotFoundException.class, () -> service.save(1L, "post", true));
        verifyNoInteractions(documentStore);
    }


    @Test
    void adminModerationQueueUsesStablePagedQueriesAndIncludesHasMoreMetadata() {
        User admin = new User();
        admin.setId(9L);
        admin.setActive(true);
        admin.setRole(new Role("ROLE_ADMIN"));
        when(users.findById(9L)).thenReturn(Optional.of(admin));
        when(documentStore.find(any(com.aloute.persistence.postgres.document.Query.class), eq(ContentReport.class)))
                .thenReturn(List.of());
        when(documentStore.find(any(com.aloute.persistence.postgres.document.Query.class), eq(Post.class)))
                .thenReturn(List.of());
        when(documentStore.find(any(com.aloute.persistence.postgres.document.Query.class), eq(Comment.class)))
                .thenReturn(List.of());

        Map<String, Object> queue = service.queue(9L, 2, 15);

        assertEquals(2, queue.get("page"));
        assertEquals(15, queue.get("size"));
        assertEquals(false, queue.get("hasMore"));
        assertTrue(((List<?>) queue.get("reports")).isEmpty());
        var queries = org.mockito.ArgumentCaptor.forClass(com.aloute.persistence.postgres.document.Query.class);
        verify(documentStore).find(queries.capture(), eq(ContentReport.class));
        assertTrue(queries.getAllValues().stream().allMatch(q -> q.skip() == 30 && q.limit() == 16));
        assertTrue(queries.getAllValues().stream().allMatch(q -> q.sort().isSorted()));
    }

    @Test
    void hostDoesNotInheritReportModerationPermission() {
        User host = new User();
        host.setId(10L);
        host.setActive(true);
        host.setRole(new Role("ROLE_HOST"));
        when(users.findById(10L)).thenReturn(Optional.of(host));
        assertThrows(ForbiddenException.class, () -> service.queue(10L));
        verifyNoInteractions(documentStore);
    }

    @Test
    void duplicateReportDoesNotResetReviewDecision() {
        ContentReport reviewed = new ContentReport();
        reviewed.setStatus("RESOLVED");
        when(documentStore.findById("1:POST:post", ContentReport.class)).thenReturn(reviewed);
        assertSame(reviewed, service.report(1L, "POST", "post", "Spam"));
        verify(documentStore, never()).insert(any(ContentReport.class));
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
        when(documentStore.find(any(Query.class), eq(UserBlock.class)))
                .thenReturn(List.of(newest, older));

        var page = service.blocks(1L, null, 1);

        assertEquals(1,page.items().size());
        assertEquals(newest.getId(),page.items().getFirst().id());
        assertEquals(newest.getOwnerId(),page.items().getFirst().ownerId());
        assertEquals(newest.getTargetId(),page.items().getFirst().targetId());
        assertEquals(newest.getCreatedAt(),page.items().getFirst().createdAt());
        assertTrue(page.hasMore());
        assertFalse(page.nextCursor().isBlank());
    }

    @Test
    void blockCursorBatchesOnlyReturnedTargetsAndKeepsOldFieldsWithoutInternalState() throws Exception {
        var block = new UserBlock(); block.setId("1:6"); block.setOwnerId(1L); block.setTargetId(6L);
        block.setRelationshipsSynced(true);
        var extra = new UserBlock(); extra.setId("1:7"); extra.setOwnerId(1L); extra.setTargetId(7L);
        when(documentStore.find(any(Query.class),eq(UserBlock.class))).thenReturn(List.of(block,extra));
        var label = mock(IUserRepository.ProfileLabelEntry.class); when(label.getId()).thenReturn(6L);
        when(label.getUsername()).thenReturn("alice"); when(label.getFullName()).thenReturn("Alice"); when(label.getAvatarUrl()).thenReturn("/alice.png");
        when(users.findProfileLabels(List.of(6L))).thenReturn(List.of(label));
        var page = service.blocks(1L,null,1);
        var item = page.items().getFirst();
        assertEquals("Alice",item.targetFullName()); assertEquals("alice",item.targetUsername());
        assertEquals("/alice.png",item.targetAvatarUrl()); assertEquals("1:6",item.id());
        assertEquals(1L,item.ownerId()); assertEquals(6L,item.targetId()); assertEquals(block.getCreatedAt(),item.createdAt());
        assertTrue(page.hasMore()); assertFalse(page.nextCursor().isEmpty());
        verify(users).findProfileLabels(List.of(6L)); verifyNoMoreInteractions(users);
        var query = org.mockito.ArgumentCaptor.forClass(Query.class); verify(documentStore).find(query.capture(),eq(UserBlock.class));
        assertTrue(query.getValue().criteria().nodes().stream().anyMatch(node -> node instanceof com.aloute.persistence.postgres.document.Criteria.ComparisonNode c && c.field().equals("ownerId") && c.value().equals(1L)));
        var json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(item);
        for (String forbidden:List.of("relationshipsSynced","password","email","token")) assertFalse(json.contains(forbidden));
    }

    @Test
    void legacyBlockListHasSameLabelsAndMissingAccountKeepsDefaultAvatar() {
        var known = new UserBlock(); known.setId("1:6"); known.setOwnerId(1L); known.setTargetId(6L);
        var missing = new UserBlock(); missing.setId("1:7"); missing.setOwnerId(1L); missing.setTargetId(7L);
        when(documentStore.find(any(Query.class),eq(UserBlock.class))).thenReturn(List.of(known,missing));
        var label = mock(IUserRepository.ProfileLabelEntry.class); when(label.getId()).thenReturn(6L);
        when(label.getUsername()).thenReturn("alice"); when(label.getFullName()).thenReturn("Alice");
        when(users.findProfileLabels(List.of(6L,7L))).thenReturn(List.of(label));
        var items = service.blocks(1L);
        assertEquals("Alice",items.getFirst().targetFullName());
        assertEquals("/images/avatar_default.png",items.getFirst().targetAvatarUrl());
        assertNull(items.getLast().targetUsername()); assertEquals("/images/avatar_default.png",items.getLast().targetAvatarUrl());
        verify(users).findProfileLabels(List.of(6L,7L)); verifyNoMoreInteractions(users);
    }

    @Test
    void emptyBlocksDoNotLoadAccountLabels() {
        assertTrue(service.blocks(1L).isEmpty()); assertTrue(service.blocks(1L,null,50).items().isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void socialListsRejectMalformedCursor() {
        assertThrows(BadRequestException.class, () -> service.requests(1L, "broken", 20));
        verify(documentStore, never()).find(any(Query.class), eq(FollowRequest.class));
    }

}
