package com.aloute.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.Comment;
import com.aloute.model.document.Post;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;

class AccountDataCleanupServiceTest {
    @Test
    void cleanupReadsPostsCommentsAndChatMediaInBoundedBatches() {
        PostgresDocumentStore documentStore = org.mockito.Mockito.mock(PostgresDocumentStore.class);
        MediaAssetLifecycleService media = org.mockito.Mockito.mock(MediaAssetLifecycleService.class);
        Post post = new Post();
        post.setId("post-1");
        post.setUserId("42");
        ChatMessage message = new ChatMessage();
        message.setId("message-1");
        message.setSenderId("42");
        message.setMediaUrl("https://res.cloudinary.com/demo/image/upload/aloute/chat/a.jpg");
        when(documentStore.find(any(Query.class), eq(Post.class))).thenReturn(List.of(post), List.of());
        when(documentStore.find(any(Query.class), eq(Comment.class))).thenReturn(List.of());
        when(documentStore.find(any(Query.class), eq(ChatMessage.class))).thenReturn(List.of(message), List.of());

        new AccountDataCleanupService(documentStore, media).deleteUserData(42L, "avatar", "cover");

        verify(documentStore, times(2)).find(
                argThat(query -> query.limit() == 100), eq(Post.class));
        verify(documentStore, times(2)).find(
                argThat(query -> query.limit() == 250), eq(ChatMessage.class));
        verify(media).queuePostMediaDeletion(post);
        verify(media).deleteIfUnreferenced(message.getMediaUrl());
        verify(media).deleteIfUnreferenced("avatar");
        verify(media).deleteIfUnreferenced("cover");
    }
}
