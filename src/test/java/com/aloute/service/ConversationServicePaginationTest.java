package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.UserBlock;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Query;

class ConversationServicePaginationTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final IGroupReadStatusRepository readStatuses =
            mock(IGroupReadStatusRepository.class);
    private final GroupService groups = mock(GroupService.class);
    private final FriendService friends = mock(FriendService.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final PostgresDocumentStore documentStore = mock(PostgresDocumentStore.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final ConversationService service = new ConversationService(
            users, readStatuses, groups, friends, presence, documentStore, access);

    @Test
    void rejectsMalformedConversationCursorBeforeRunningQueries() {
        assertThrows(
                BadRequestException.class,
                () -> service.getConversationPage(1L, "not-a-valid-cursor", 30));

        verify(groups, never()).getAllMyGroupIds(any());
    }

    @Test
    void unreadDirectOnlyChecksStatusOfUsersWhoActuallySentUnreadMessages() {
        when(documentStore.find(any(Query.class), eq(UserBlock.class))).thenReturn(List.of());
        when(documentStore.countGroupedBy(any(Query.class), eq(ChatMessage.class), any(String[].class)))
                .thenReturn(Map.of(List.of("2"), 4L));
        when(users.findActiveIdsByIdIn(anyCollection())).thenReturn(List.of(2L));

        assertEquals(4L, service.countUnreadDirect(1L));

        verify(users).findActiveIdsByIdIn(anyCollection());
        verify(users, never()).findInactiveIds();
    }

    @Test
    void unreadGroupsUseOnlyMembershipIdsAndScopedReadTimes() {
        when(documentStore.find(any(Query.class), eq(UserBlock.class))).thenReturn(List.of());
        when(readStatuses.findReadTimes(eq(1L), anyCollection())).thenReturn(List.of());
        when(documentStore.countGroupedBy(any(Query.class), eq(ChatMessage.class), any(String[].class)))
                .thenReturn(Map.of());

        assertEquals(0L, service.countUnreadGroupsByIds(List.of(10L, 11L), 1L));

        verify(readStatuses).findReadTimes(eq(1L), eq(List.of(10L, 11L)));
        verify(readStatuses, never()).findByUserId(1L);
        verify(groups, never()).getGroupsByIds(anyCollection());
    }
}
