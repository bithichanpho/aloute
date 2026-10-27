package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.common.exception.*;
import com.aloute.model.document.FollowRequest;
import com.aloute.notifications.api.Notifications;
import com.aloute.persistence.postgres.document.*;
import com.aloute.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.*;

class FollowerRemovalTest {
    final IFriendRepository friends = mock(IFriendRepository.class);
    final Notifications notifications = mock(Notifications.class);
    final ContentAccessService access = mock(ContentAccessService.class);
    final PostgresDocumentStore store = mock(PostgresDocumentStore.class);
    final FeedCacheInvalidator feeds = mock(FeedCacheInvalidator.class);
    final FriendService service = new FriendService(friends, notifications, access, store, mock(IUserRepository.class), feeds);

    @Test void removesOnlyIncomingEdgeAndStaleRequestAndReturnsAuthoritativeCount() {
        when(friends.removeFollower(1L, 2L)).thenReturn(1);
        when(friends.countByFollowedId(1L)).thenReturn(7L);
        var result = service.removeFollower(1L, 2L);
        assertTrue(result.removed()); assertEquals(7, result.followerCount());
        verify(access).requireProfile(1L, 1L);
        verify(friends).removeFollower(1L, 2L);
        verify(friends).countByFollowedId(1L);
        verifyNoMoreInteractions(friends); verifyNoInteractions(notifications);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(store).remove(query.capture(), eq(FollowRequest.class));
        var condition = (Criteria.ComparisonNode) query.getValue().criteria().nodes().getFirst();
        assertEquals("_id", condition.field()); assertEquals("2:1", condition.value());
        verify(feeds).invalidateViewer(2L); verify(feeds).invalidateViewer(1L);
    }

    @Test void repeatRemovalDoesNotToggleTheRelationshipBackOn() {
        var result = service.removeFollower(1L, 2L);
        assertFalse(result.removed()); assertEquals(0, result.followerCount());
        verify(friends, never()).save(any()); verifyNoInteractions(notifications);
    }

    @Test void rejectsAnonymousSelfAndInvalidTargetBeforeAnyWrite() {
        assertThrows(UnauthorizedException.class, () -> service.removeFollower(null, 2L));
        assertThrows(BadRequestException.class, () -> service.removeFollower(1L, 1L));
        assertThrows(BadRequestException.class, () -> service.removeFollower(1L, 0L));
        assertThrows(BadRequestException.class, () -> service.removeFollower(1L, null));
        verifyNoInteractions(friends, store, feeds);
    }

    @Test void inactiveOwnerCannotRemoveRelationships() {
        doThrow(new ForbiddenException("inactive")).when(access).requireProfile(1L, 1L);
        assertThrows(ForbiddenException.class, () -> service.removeFollower(1L, 2L));
        verifyNoInteractions(friends, store, feeds);
    }

    @Test void feedInvalidationWaitsForCommitAndNeverRunsOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.removeFollower(1L, 2L); verifyNoInteractions(feeds);
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verifyNoInteractions(feeds);
        } finally { TransactionSynchronizationManager.clear(); }
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.removeFollower(1L, 2L); verifyNoInteractions(feeds);
            TransactionSynchronizationUtils.triggerAfterCommit();
            verify(feeds).invalidateViewer(1L); verify(feeds).invalidateViewer(2L);
        } finally { TransactionSynchronizationManager.clear(); }
    }

    @Test void publicProxyEvictsOnlyAffectedRelationshipAndSuggestionKeys() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.register(CachingConfiguration.class);
            context.registerBean(org.springframework.cache.CacheManager.class, () ->
                    new org.springframework.cache.caffeine.CaffeineCacheManager("relationshipIds", "suggestedUsers"));
            context.registerBean(FriendService.class, () -> service); context.refresh();
            var caches = context.getBean(org.springframework.cache.CacheManager.class);
            var relationships = caches.getCache("relationshipIds");
            relationships.put("followers:1", java.util.List.of(2L));
            relationships.put("following:2", java.util.List.of(1L));
            relationships.put("following:1", java.util.List.of(2L));
            var suggestions = caches.getCache("suggestedUsers");
            String suffix = ":" + DiscoveryService.FEED_SUGGESTION_LIMIT;
            suggestions.put("1" + suffix, java.util.List.of()); suggestions.put("2" + suffix, java.util.List.of());
            context.getBean(FriendService.class).removeFollower(1L, 2L);
            assertNull(relationships.get("followers:1")); assertNull(relationships.get("following:2"));
            assertNotNull(relationships.get("following:1"));
            assertNull(suggestions.get("1" + suffix)); assertNull(suggestions.get("2" + suffix));
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.cache.annotation.EnableCaching
    static class CachingConfiguration {}
}
