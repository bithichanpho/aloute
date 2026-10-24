package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.document.Comment;
import com.aloute.model.document.CommentReaction;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.Like;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.Repost;
import com.aloute.model.document.SavedPost;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;

class CommunityDataCleanupServiceTest {
    @Test
    void removesCommunityContentDependenciesAndQueuesMediaDeletion() {
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        PostgresTransactionRetryExecutor transactions = mock(PostgresTransactionRetryExecutor.class);
        MediaAssetLifecycleService media = mock(MediaAssetLifecycleService.class);
        INotificationRepository notifications = mock(INotificationRepository.class);
        INotificationOutboxRepository outbox = mock(INotificationOutboxRepository.class);
        FeedCacheInvalidator cache = mock(FeedCacheInvalidator.class);

        Post post = new Post();
        post.setId("post-1");
        post.setCommunityId("42");
        post.setImageUrl("https://res.cloudinary.com/demo/image/upload/aloute/post/p.png");
        Comment comment = new Comment();
        comment.setId("comment-1");
        comment.setPostId("post-1");

        AtomicBoolean postsReturned = new AtomicBoolean();
        when(documentStore.find(any(Query.class), eq(Post.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            if (query.criteria() != null && query.criteria().nodes().stream()
                    .anyMatch(node -> containsField(node, "repostOfId"))) return List.of();
            return postsReturned.getAndSet(true) ? List.of() : List.of(post);
        });
        AtomicBoolean commentsReturned = new AtomicBoolean();
        when(documentStore.find(any(Query.class), eq(Comment.class))).thenAnswer(invocation ->
                commentsReturned.getAndSet(true) ? List.of() : List.of(comment));
        when(transactions.execute(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });

        CommunityDataCleanupService service = new CommunityDataCleanupService(
                documentStore, transactions, media, notifications, outbox, cache);
        service.deleteCommunityData(42L, "avatar", "cover");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> postIds = ArgumentCaptor.forClass(List.class);
        verify(notifications).deleteByReferenceIdIn(postIds.capture());
        assertEquals(List.of("post-1"), postIds.getValue());
        verify(outbox).deleteByReferenceIdIn(List.of("post-1"));
        verify(documentStore).remove(any(Query.class), eq(CommentReaction.class));
        verify(documentStore).remove(any(Query.class), eq(Comment.class));
        verify(documentStore).remove(any(Query.class), eq(Like.class));
        verify(documentStore).remove(any(Query.class), eq(Repost.class));
        verify(documentStore).remove(any(Query.class), eq(SavedPost.class));
        verify(documentStore).remove(any(Query.class), eq(PostRevision.class));
        verify(documentStore, atLeastOnce()).remove(any(Query.class), eq(ContentReport.class));
        verify(documentStore).remove(any(Query.class), eq(Post.class));
        verify(media).queuePostMediaDeletion(post);
        verify(media).deleteIfUnreferenced("avatar");
        verify(media).deleteIfUnreferenced("cover");
        verify(notifications).deleteByReferenceIdAndTypeIn(eq("42"), anyCollection());
        verify(outbox).deleteByReferenceIdAndNotificationTypeIn(eq("42"), anyCollection());
        verify(cache).invalidateAll();
    }

    @Test
    void doesNotDeleteMediaOrCacheWhenPostgresCleanupFails() {
        PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
        when(documentStore.find(any(Query.class), eq(Post.class)))
                .thenThrow(new IllegalStateException("documentStore unavailable"));
        MediaAssetLifecycleService media = mock(MediaAssetLifecycleService.class);
        FeedCacheInvalidator cache = mock(FeedCacheInvalidator.class);
        CommunityDataCleanupService service = new CommunityDataCleanupService(
                documentStore, null, media, mock(INotificationRepository.class),
                mock(INotificationOutboxRepository.class), cache);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.deleteCommunityData(42L, "avatar", "cover"));

        verify(media, never()).deleteIfUnreferenced(any());
        verify(cache, never()).invalidateAll();
    }
    private static boolean containsField(Criteria.Node node, String field) {
        if (node instanceof Criteria.ComparisonNode comparison) {
            return field.equals(comparison.field());
        }
        if (node instanceof Criteria.TextNode text) {
            return field.equals(text.field());
        }
        if (node instanceof Criteria.LogicalNode logical) {
            return logical.children().stream().anyMatch(child -> containsField(child, field));
        }
        return false;
    }

}
