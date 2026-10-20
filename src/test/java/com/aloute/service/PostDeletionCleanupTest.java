package com.aloute.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.model.document.Comment;
import com.aloute.model.document.CommentReaction;
import com.aloute.model.document.ContentReport;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.model.document.SavedPost;
import com.aloute.repository.ICommentRepository;
import com.aloute.repository.IFriendRepository;
import com.aloute.repository.ILikeRepository;
import com.aloute.repository.INotificationOutboxRepository;
import com.aloute.repository.INotificationRepository;
import com.aloute.repository.IPostRepository;
import com.aloute.repository.IRepostRepository;
import com.aloute.repository.IUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class PostDeletionCleanupTest {
    @Test
    void postDeletionRemovesAllDependantsAndDurablyQueuesMedia() {
        IPostRepository posts = mock(IPostRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        ILikeRepository likes = mock(ILikeRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        ICommentRepository comments = mock(ICommentRepository.class);
        INotificationRepository notifications = mock(INotificationRepository.class);
        INotificationOutboxRepository outbox = mock(INotificationOutboxRepository.class);
        IRepostRepository reposts = mock(IRepostRepository.class);
        MediaAssetLifecycleService media = mock(MediaAssetLifecycleService.class);
        FeedCacheInvalidator cache = mock(FeedCacheInvalidator.class);

        Post post = new Post();
        post.setId("post-1");
        post.setUserId("7");
        post.setImageUrl("managed-image");
        Comment comment = new Comment();
        comment.setId("comment-1");
        when(posts.findById("post-1")).thenReturn(Optional.of(post));
        when(mongo.find(any(Query.class), eq(Comment.class))).thenReturn(List.of(comment));

        PostService service = new PostService(
                posts, users, likes, mock(ModerationService.class),
                mock(NotificationService.class), mongo, comments, notifications, reposts,
                mock(ContentAccessService.class), mock(RepostPresentationService.class),
                mock(PostResponseMapper.class), mock(PostModerationCoordinator.class), media,
                cache, null, mock(IFriendRepository.class), outbox);

        service.deletePost("post-1", 7L);

        verify(likes).deleteByPostId("post-1");
        verify(comments).deleteByPostId("post-1");
        verify(reposts).deleteByPostId("post-1");
        verify(mongo).remove(any(Query.class), eq(CommentReaction.class));
        verify(mongo).remove(any(Query.class), eq(SavedPost.class));
        verify(mongo).remove(any(Query.class), eq(PostRevision.class));
        verify(mongo, org.mockito.Mockito.atLeastOnce())
                .remove(any(Query.class), eq(ContentReport.class));
        verify(posts).delete(post);
        verify(media).queuePostMediaDeletion(post);
        verify(notifications).deleteByReferenceId("post-1");
        verify(outbox).deleteByReferenceIdIn(List.of("post-1"));
        verify(cache).invalidateAll();
    }
}
