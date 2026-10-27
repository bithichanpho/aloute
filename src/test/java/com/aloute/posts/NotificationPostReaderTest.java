package com.aloute.posts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.communities.api.CommunityReviewAccess;
import com.aloute.model.document.CommunityPostStatus;
import com.aloute.model.document.Post;
import com.aloute.posts.infrastructure.NotificationPostReader;
import com.aloute.repository.IPostRepository;
import com.aloute.service.ContentAccessService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationPostReaderTest {
    @Test
    void moderationDeliveryLoadsOwnersInOneBulkLookup() {
        var posts = mock(IPostRepository.class);
        var post = new Post(); post.setId("post"); post.setUserId("7");
        when(posts.findAllById(java.util.List.of("post", "missing"))).thenReturn(java.util.List.of(post));
        var reader = new NotificationPostReader(posts, mock(ContentAccessService.class));
        assertEquals(java.util.Map.of("post", 7L), reader.ownersOf(java.util.List.of("post", "missing", "post")));
        verify(posts).findAllById(java.util.List.of("post", "missing"));
        verifyNoMoreInteractions(posts);
    }
    @Test
    void reviewNotificationRequiresBothPendingPostAndCurrentCommunityPermission() {
        var posts = mock(IPostRepository.class);
        var visibility = mock(ContentAccessService.class);
        var communities = mock(CommunityReviewAccess.class);
        var reader = new NotificationPostReader(posts, visibility, communities);
        Post post = new Post();
        post.setCommunityId("42");
        post.setCommunityPostStatus(CommunityPostStatus.PENDING);
        when(posts.findById("p")).thenReturn(Optional.of(post));
        when(communities.mayReviewPosts("42", 7L)).thenReturn(true, false);
        assertTrue(reader.canReviewCommunityPost("p", 7L));
        assertFalse(reader.canReviewCommunityPost("p", 7L));
        post.setCommunityPostStatus(CommunityPostStatus.APPROVED);
        clearInvocations(communities);
        assertFalse(reader.canReviewCommunityPost("p", 7L));
        verifyNoInteractions(communities);
    }

    @Test
    void ordinaryPostNotificationsUseAuthoritativeObjectVisibility() {
        var posts = mock(IPostRepository.class);
        var visibility = mock(ContentAccessService.class);
        var reader = new NotificationPostReader(posts, visibility);
        Post post = new Post();
        when(posts.findById("p")).thenReturn(Optional.of(post));
        when(visibility.canRead(post, 7L)).thenReturn(true, false);
        assertTrue(reader.isReadable("p", 7L));
        assertFalse(reader.isReadable("p", 7L));
        assertFalse(reader.isReadable("missing", 7L));
    }
}
