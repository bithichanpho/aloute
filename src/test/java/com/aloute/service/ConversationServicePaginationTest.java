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
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

class ConversationServicePaginationTest {
    private final IUserRepository users = mock(IUserRepository.class);
    private final IGroupReadStatusRepository readStatuses =
            mock(IGroupReadStatusRepository.class);
    private final GroupService groups = mock(GroupService.class);
    private final FriendService friends = mock(FriendService.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ContentAccessService access = mock(ContentAccessService.class);
    private final ConversationService service = new ConversationService(
            users, readStatuses, groups, friends, presence, mongo, access);

    @Test
    void rejectsMalformedConversationCursorBeforeRunningQueries() {
        assertThrows(
                BadRequestException.class,
                () -> service.getConversationPage(1L, "not-a-valid-cursor", 30));

        verify(groups, never()).getAllMyGroupIds(any());
    }

    @Test
    void unreadDirectOnlyChecksStatusOfUsersWhoActuallySentUnreadMessages() {
        when(mongo.find(any(Query.class), eq(UserBlock.class))).thenReturn(List.of());
        Document unreadBySender = new Document("_id", "2").append("count", 4L);
        when(mongo.aggregate(any(Aggregation.class), eq(ChatMessage.class), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(unreadBySender), new Document()));
        when(users.findActiveIdsByIdIn(anyCollection())).thenReturn(List.of(2L));

        assertEquals(4L, service.countUnreadDirect(1L));

        verify(users).findActiveIdsByIdIn(anyCollection());
        verify(users, never()).findInactiveIds();
    }

    @Test
    void unreadGroupsUseOnlyMembershipIdsAndScopedReadTimes() {
        when(mongo.find(any(Query.class), eq(UserBlock.class))).thenReturn(List.of());
        when(readStatuses.findReadTimes(eq(1L), anyCollection())).thenReturn(List.of());
        when(mongo.aggregate(any(Aggregation.class), eq(ChatMessage.class), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(), new Document()));

        assertEquals(0L, service.countUnreadGroupsByIds(List.of(10L, 11L), 1L));

        verify(readStatuses).findReadTimes(eq(1L), eq(List.of(10L, 11L)));
        verify(readStatuses, never()).findByUserId(1L);
        verify(groups, never()).getGroupsByIds(anyCollection());
    }
}
