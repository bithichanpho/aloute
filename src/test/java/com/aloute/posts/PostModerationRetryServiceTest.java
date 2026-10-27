package com.aloute.posts;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.common.exception.*;
import com.aloute.identity.api.ActionRateLimit;
import com.aloute.model.document.*;
import com.aloute.moderation.api.ModerationPolicy;
import com.aloute.persistence.postgres.document.*;
import com.aloute.posts.api.PostReviewDispatch;
import com.aloute.posts.application.PostModerationRetryService;
import com.aloute.posts.domain.PostAccess;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.*;

class PostModerationRetryServiceTest {
    final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    final PostAccess access = mock(PostAccess.class);
    final ActionRateLimit limits = mock(ActionRateLimit.class);
    final ModerationPolicy moderation = mock(ModerationPolicy.class);
    final PostReviewDispatch dispatch = mock(PostReviewDispatch.class);
    final PostModerationRetryService service = new PostModerationRetryService(store, access, limits, moderation, dispatch);

    Post outage() {
        var post = new Post(); post.setId("p"); post.setUserId("1"); post.setContent("text");
        post.setVisibility(PostVisibility.FRIENDS); post.setModerationStatus(ModerationStatus.FLAGGED);
        post.setModerationFailureReason("TEXT_SERVICE_UNAVAILABLE"); post.setModerationAttempts(5);
        when(access.requireReadable("p", 1L)).thenReturn(post);
        when(moderation.isEnabled()).thenReturn(true);
        when(store.updateFirst(any(), any(), eq(Post.class))).thenReturn(new UpdateResult(1, 1));
        return post;
    }

    @Test void authenticationOwnershipAndContentDecisionsCannotBeBypassed() {
        assertThrows(UnauthorizedException.class, () -> service.retry(null, "p"));
        verifyNoInteractions(access, store, limits, dispatch);
        var post = outage(); post.setUserId("2");
        assertThrows(ForbiddenException.class, () -> service.retry(1L, "p"));
        post.setUserId("1");
        for (var status : List.of(ModerationStatus.APPROVED, ModerationStatus.HIDDEN)) {
            post.setModerationStatus(status);
            assertThrows(BadRequestException.class, () -> service.retry(1L, "p"));
        }
        post.setModerationStatus(ModerationStatus.FLAGGED);
        for (String reason : Arrays.asList(null, "TOXIC_CONTENT", "INVALID_SERVICE_URL", "HTTP_401", "MAX_RETRIES_EXCEEDED")) {
            post.setModerationFailureReason(reason);
            assertThrows(BadRequestException.class, () -> service.retry(1L, "p"));
        }
        verify(store, never()).updateFirst(any(), any(), any()); verifyNoInteractions(dispatch, limits);
    }

    @Test void retryQueuesExistingContentWithoutApprovingOrChangingItsAudience() {
        var post = outage(); service.retry(1L, "p");
        var query = ArgumentCaptor.forClass(Query.class); var update = ArgumentCaptor.forClass(Update.class);
        verify(store).updateFirst(query.capture(), update.capture(), eq(Post.class));
        Map<String,Object> compare = new HashMap<>();
        for (var node : query.getValue().criteria().nodes()) {
            var comparison = (Criteria.ComparisonNode) node; compare.put(comparison.field(), comparison.value());
        }
        assertEquals(ModerationStatus.FLAGGED, compare.get("moderationStatus"));
        assertEquals("1", compare.get("userId"));
        assertEquals("TEXT_SERVICE_UNAVAILABLE", compare.get("moderationFailureReason"));
        assertTrue(compare.keySet().containsAll(List.of("updatedAt","moderationLastAttemptAt","content","imageUrl","videoUrl")));
        assertTrue(update.getValue().operations().stream().anyMatch(op -> op.field().equals("moderationStatus") && op.value() == ModerationStatus.PENDING));
        assertTrue(update.getValue().operations().stream().noneMatch(op -> Set.of("visibility","content","imageUrl","videoUrl","communityPostStatus","notifyMentionsAfterModeration").contains(op.field())));
        assertEquals(PostVisibility.FRIENDS, post.getVisibility());
        verify(dispatch).submit("p"); verify(limits).checkAndRecord(eq("POST_MODERATION_RETRY"), eq("1"), eq(5), any(), any(), any(), any());
    }

    @Test void pendingClickIsIdempotentAndDisabledModerationNeverApproves() {
        var post = outage(); post.setModerationStatus(ModerationStatus.PENDING);
        service.retry(1L,"p"); verifyNoInteractions(store, limits, dispatch);
        post.setModerationStatus(ModerationStatus.FLAGGED); when(moderation.isEnabled()).thenReturn(false);
        assertThrows(BadRequestException.class, () -> service.retry(1L,"p")); verifyNoInteractions(store, limits, dispatch);
    }

    @Test void concurrentRetryOrAdministratorDecisionOnlyAllowsOneDispatch() throws Exception {
        outage(); var claimed = new AtomicBoolean();
        when(store.updateFirst(any(),any(),eq(Post.class))).thenAnswer(call -> claimed.compareAndSet(false,true) ? new UpdateResult(1,1) : new UpdateResult(0,0));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            Callable<Boolean> retry = () -> { latch.await(); try { service.retry(1L,"p"); return true; } catch (BadRequestException conflict) { return false; } };
            var first = executor.submit(retry); var second = executor.submit(retry); latch.countDown();
            assertNotEquals(first.get(5,TimeUnit.SECONDS), second.get(5,TimeUnit.SECONDS));
        }
        verify(dispatch, times(1)).submit("p");
    }

    @Test void transactionRollbackNeverDispatchesAndCommitUsesExistingRecoveryOnRejection() {
        outage(); TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.retry(1L,"p"); verifyNoInteractions(dispatch);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(dispatch);
        } finally { TransactionSynchronizationManager.clear(); }
        TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.retry(1L,"p"); verifyNoInteractions(dispatch);
            doThrow(new IllegalStateException("rejected")).when(dispatch).submit("p");
            assertDoesNotThrow(TransactionSynchronizationUtils::triggerAfterCommit);
            verify(dispatch).submit("p");
        } finally { TransactionSynchronizationManager.clear(); }
    }
}
