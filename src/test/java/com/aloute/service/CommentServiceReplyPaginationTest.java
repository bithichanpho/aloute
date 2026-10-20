package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.dto.response.CommentCursorPage;
import com.aloute.model.document.Comment;
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
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CommentServiceReplyPaginationTest {
    private ICommentRepository comments;
    private IUserRepository users;
    private MongoTemplate mongo;
    private ContentAccessService access;
    private CommentService service;

    @BeforeEach
    void setUp() {
        comments = mock(ICommentRepository.class);
        users = mock(IUserRepository.class);
        mongo = mock(MongoTemplate.class);
        access = mock(ContentAccessService.class);
        service = new CommentService(
                comments,
                mock(PostService.class),
                users,
                mongo,
                mock(CommentPersistenceService.class),
                access,
                mock(ModerationService.class),
                mock(AuthRateLimitService.class));

        User viewer = user(1L, "viewer");
        AloutePrincipal principal = new AloutePrincipal(viewer);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void repliesUseCursorPagesAndDoNotMixTopLevelComments() {
        Comment parent = comment("parent", "post-1", "2", null, 10);
        Comment first = comment("reply-1", "post-1", "3", "parent", 11);
        Comment second = comment("reply-2", "post-1", "3", "parent", 12);
        Comment third = comment("reply-3", "post-1", "3", "parent", 13);
        when(comments.findById("parent")).thenReturn(Optional.of(parent));
        when(comments.findAllById(org.mockito.ArgumentMatchers.<String>anyIterable()))
                .thenReturn(List.of(parent));
        when(users.findAllById(org.mockito.ArgumentMatchers.<Long>anyIterable()))
                .thenReturn(List.of(user(2L, "parent-author"), user(3L, "reply-author")));
        when(access.visibleUserIds(eq(1L), any())).thenReturn(Set.of(2L, 3L));
        when(mongo.find(any(Query.class), eq(Comment.class)))
                .thenReturn(List.of(first, second, third))
                .thenReturn(List.of(third));

        CommentCursorPage page1 = service.getReplies("parent", 1L, null, 2);
        assertEquals(2, page1.items().size());
        assertTrue(page1.hasMore());
        assertFalse(page1.nextCursor().isBlank());
        assertEquals("reply-1", page1.items().get(0).getId());

        CommentCursorPage page2 = service.getReplies("parent", 1L, page1.nextCursor(), 2);
        assertEquals(1, page2.items().size());
        assertFalse(page2.hasMore());
        assertEquals("reply-3", page2.items().get(0).getId());
        assertEquals("parent", page2.items().get(0).getParentCommentId());
    }

    @Test
    void invalidCursorIsRejected() {
        when(comments.findById("parent")).thenReturn(Optional.of(comment("parent", "post-1", "2", null, 10)));
        assertThrows(BadRequestException.class, () -> service.getReplies("parent", 1L, "!!!invalid!!!", 20));
    }

    private static Comment comment(String id, String postId, String userId, String parentId, int seconds) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setPostId(postId);
        comment.setUserId(userId);
        comment.setParentCommentId(parentId);
        comment.setContent("reply body");
        comment.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusSeconds(seconds));
        return comment;
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPassword("unused");
        user.setFullName(username);
        user.setRole(new Role("ROLE_USER"));
        return user;
    }
}
