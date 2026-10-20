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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class CommunityDataCleanupServiceTest {
    @Test
    void removesCommunityContentDependenciesAndQueuesMediaDeletion() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoTransactionRetryExecutor transactions = mock(MongoTransactionRetryExecutor.class);
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
        when(mongo.find(any(Query.class), eq(Post.class))).thenAnswer(invocation -> {
            Query query = invocation.getArgument(0);
            if (query.getQueryObject().containsKey("repostOfId")) return List.of();
            return postsReturned.getAndSet(true) ? List.of() : List.of(post);
        });
        AtomicBoolean commentsReturned = new AtomicBoolean();
        when(mongo.find(any(Query.class), eq(Comment.class))).thenAnswer(invocation ->
                commentsReturned.getAndSet(true) ? List.of() : List.of(comment));
        when(transactions.execute(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });

        CommunityDataCleanupService service = new CommunityDataCleanupService(
                mongo, transactions, media, notifications, outbox, cache);
        service.deleteCommunityData(42L, "avatar", "cover");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> postIds = ArgumentCaptor.forClass(List.class);
        verify(notifications).deleteByReferenceIdIn(postIds.capture());
        assertEquals(List.of("post-1"), postIds.getValue());
        verify(outbox).deleteByReferenceIdIn(List.of("post-1"));
        verify(mongo).remove(any(Query.class), eq(CommentReaction.class));
        verify(mongo).remove(any(Query.class), eq(Comment.class));
        verify(mongo).remove(any(Query.class), eq(Like.class));
        verify(mongo).remove(any(Query.class), eq(Repost.class));
        verify(mongo).remove(any(Query.class), eq(SavedPost.class));
        verify(mongo).remove(any(Query.class), eq(PostRevision.class));
        verify(mongo, atLeastOnce()).remove(any(Query.class), eq(ContentReport.class));
        verify(mongo).remove(any(Query.class), eq(Post.class));
        verify(media).queuePostMediaDeletion(post);
        verify(media).deleteIfUnreferenced("avatar");
        verify(media).deleteIfUnreferenced("cover");
        verify(notifications).deleteByReferenceIdAndTypeIn(eq("42"), anyCollection());
        verify(outbox).deleteByReferenceIdAndNotificationTypeIn(eq("42"), anyCollection());
        verify(cache).invalidateAll();
    }

    @Test
    void doesNotDeleteMediaOrCacheWhenMongoCleanupFails() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.find(any(Query.class), eq(Post.class)))
                .thenThrow(new IllegalStateException("mongo unavailable"));
        MediaAssetLifecycleService media = mock(MediaAssetLifecycleService.class);
        FeedCacheInvalidator cache = mock(FeedCacheInvalidator.class);
        CommunityDataCleanupService service = new CommunityDataCleanupService(
                mongo, null, media, mock(INotificationRepository.class),
                mock(INotificationOutboxRepository.class), cache);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.deleteCommunityData(42L, "avatar", "cover"));

        verify(media, never()).deleteIfUnreferenced(any());
        verify(cache, never()).invalidateAll();
    }
}
