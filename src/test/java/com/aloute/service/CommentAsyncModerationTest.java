package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.dto.request.CreateCommentRequest;
import com.aloute.dto.response.CommentResponse;
import com.aloute.model.document.Comment;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IUserRepository;
import com.aloute.security.AloutePrincipal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CommentAsyncModerationTest {
    private ICommentRepository comments;
    private IUserRepository users;
    private ContentAccessService access;
    private ModerationService moderation;
    private CommentPersistenceService persistence;
    private CommentModerationCoordinator coordinator;
    private CommentService service;

    @BeforeEach
    void setUp() {
        comments = mock(ICommentRepository.class);
        users = mock(IUserRepository.class);
        access = mock(ContentAccessService.class);
        moderation = mock(ModerationService.class);
        persistence = mock(CommentPersistenceService.class);
        coordinator = mock(CommentModerationCoordinator.class);
        service = new CommentService(comments, mock(PostService.class), users, mock(MongoTemplate.class),
                persistence, access, moderation, mock(AuthRateLimitService.class), coordinator);

        User viewer = new User();
        viewer.setId(1L);
        viewer.setUsername("viewer");
        viewer.setFullName("Viewer");
        viewer.setPassword("unused");
        viewer.setRole(new Role("ROLE_USER"));
        AloutePrincipal principal = new AloutePrincipal(viewer);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        when(users.findAllById(org.mockito.ArgumentMatchers.<Long>anyIterable()))
                .thenReturn(List.of(viewer));
        when(access.visibleUserIds(eq(1L), anyCollection())).thenReturn(Set.of(1L));
        when(moderation.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createCommentPublishesImmediatelyWithoutGlobalModeration() {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setContent("Hello from ALOUTE");
        Comment saved = comment("comment-1", "1", request.getContent());
        saved.setModerationStatus(ModerationStatus.APPROVED);
        when(persistence.create(eq("post-1"), eq(1L), eq(request), eq(ModerationStatus.APPROVED), eq(null)))
                .thenReturn(saved);

        CommentResponse response = service.createComment("post-1", 1L, request);

        assertEquals("comment-1", response.getId());
        assertEquals(ModerationStatus.APPROVED, response.getModerationStatus());
        verify(coordinator, never()).submit(any());
        verify(moderation, never()).check(any());
    }

    @Test
    void editCommentRemainsPublishedWithoutGlobalModeration() {
        Comment original = comment("comment-2", "1", "old text");
        when(comments.findById("comment-2")).thenReturn(Optional.of(original));
        when(comments.save(any(Comment.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CommentResponse response = service.editComment("comment-2", 1L, "new text");

        assertNotNull(response);
        assertEquals(ModerationStatus.APPROVED, response.getModerationStatus());
        assertEquals("new text", response.getContent());
        verify(coordinator, never()).invalidateFeedCache();
        verify(coordinator, never()).submit(any());
        verify(moderation, never()).check(any());
    }

    @Test
    void hiddenCommentCannotBeEditedBackIntoCirculation() {
        Comment hidden = comment("comment-3", "1", "bad text");
        hidden.setModerationStatus(ModerationStatus.HIDDEN);
        when(comments.findById("comment-3")).thenReturn(Optional.of(hidden));

        assertThrows(
                com.aloute.common.exception.ForbiddenException.class,
                () -> service.editComment("comment-3", 1L, "innocent text"));

        verify(persistence, never()).updatePendingForModeration(any(), any());
        verify(comments, never()).save(any());
        verify(coordinator, never()).submit(any());
        assertEquals(ModerationStatus.HIDDEN, hidden.getModerationStatus());
    }

    private static Comment comment(String id, String userId, String content) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId("post-1");
        comment.setUserId(userId);
        comment.setContent(content);
        comment.setModerationStatus(ModerationStatus.PENDING);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setModerationCountersApplied(false);
        return comment;
    }
}
