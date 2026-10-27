package com.aloute.schema;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.aloute.model.document.*;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import com.aloute.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Actual aggregate SQL against an owned disposable database; no application startup. */
class ProfilePostCountIT {
    @Test void profileTotalIncludesEveryAudienceAndMediaFormatAndUpdatesAfterChanges() {
        try (var database = DisposablePostgres.create()) {
            database.migrations().load().migrate();
            var store = spy(new PostgresDocumentStore(database.jdbc, new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()));
            var service = new PostService(mock(IPostRepository.class), mock(IUserRepository.class), mock(ILikeRepository.class),
                    mock(ModerationService.class), mock(NotificationService.class), store, mock(ICommentRepository.class),
                    mock(INotificationRepository.class), mock(IRepostRepository.class), mock(ContentAccessService.class),
                    mock(RepostPresentationService.class), mock(PostResponseMapper.class), mock(PostModerationCoordinator.class), mock(MediaAssetLifecycleService.class));
            int expected = 0;
            for (var visibility : PostVisibility.values()) {
                for (var status : List.of(ModerationStatus.APPROVED, ModerationStatus.PENDING, ModerationStatus.FLAGGED)) {
                    var post = post(visibility + "-" + status, "1", visibility, status);
                    if (status == ModerationStatus.PENDING) post.setImageUrl("/test-only-image.jpg");
                    if (status == ModerationStatus.FLAGGED) post.setVideoUrl("/test-only-video.mp4");
                    store.save(post); expected++;
                }
                store.save(post(visibility + "-hidden", "1", visibility, ModerationStatus.HIDDEN));
                store.save(post(visibility + "-another-author", "2", visibility, ModerationStatus.APPROVED));
                var forwarded = post(visibility + "-empty-forward", "1", visibility, ModerationStatus.APPROVED);
                forwarded.setRepostOfId("original"); forwarded.setContent(""); store.save(forwarded);
            }
            var quote = post("quote", "1", PostVisibility.ONLY_ME, ModerationStatus.APPROVED);
            quote.setRepostOfId("original"); store.save(quote); expected++;
            var legacy = post("legacy", "1", null, ModerationStatus.APPROVED); store.save(legacy); expected++;
            var community = post("community", "1", PostVisibility.CLOSE_FRIENDS, ModerationStatus.APPROVED);
            community.setCommunityId("test-community"); store.save(community); expected++;
            clearInvocations(store);
            assertEquals(expected, service.countPostsByUser(1L));
            verify(store).count(org.mockito.ArgumentMatchers.any(Query.class), org.mockito.ArgumentMatchers.eq(Post.class));
            verifyNoMoreInteractions(store); // Count runs one aggregate, never loads a page/dataset.
            assertEquals(PostVisibility.values().length, service.countPostsByUser(2L));
            assertEquals(0, service.countPostsByUser(999L));

            var changed = store.findById("PUBLIC-APPROVED", Post.class);
            for (var visibility : PostVisibility.values()) {
                changed.setVisibility(visibility); store.save(changed);
                assertEquals(expected, service.countPostsByUser(1L));
            }
            changed.setModerationStatus(ModerationStatus.HIDDEN); store.save(changed);
            assertEquals(--expected, service.countPostsByUser(1L));
            store.remove(Query.query(Criteria.where("_id").is("quote")), Post.class);
            assertEquals(--expected, service.countPostsByUser(1L));
            store.save(post("new-private-post", "1", PostVisibility.ONLY_ME, ModerationStatus.PENDING));
            assertEquals(++expected, service.countPostsByUser(1L));
        }
    }

    private Post post(String id, String author, PostVisibility audience, ModerationStatus status) {
        var post = new Post(); post.setId(id); post.setUserId(author); post.setContent("Test-only content");
        post.setVisibility(audience); post.setModerationStatus(status); return post;
    }
}
