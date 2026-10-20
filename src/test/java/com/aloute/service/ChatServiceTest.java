package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.model.document.ChatMessage;
import com.aloute.model.entity.User;
import com.aloute.dto.response.GroupResponse;
import com.aloute.repository.IChatMessageRepository;
import com.aloute.repository.IGroupReadStatusRepository;
import com.aloute.repository.IUserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.List;

class ChatServiceTest {
    @Test
    void cursorHistoryReturnsChronologicalItemsAndNextCursor() {
        IChatMessageRepository messages = mock(IChatMessageRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        ContentAccessService access = mock(ContentAccessService.class);
        ChatMessage newest = chat("m3", "1", "newest");
        ChatMessage middle = chat("m2", "2", "middle");
        ChatMessage extra = chat("m1", "1", "extra");
        when(mongo.find(any(Query.class), eq(ChatMessage.class)))
                .thenReturn(new java.util.ArrayList<>(List.of(newest, middle, extra)));
        when(users.findAllById(org.mockito.ArgumentMatchers.<Iterable<Long>>any()))
                .thenReturn(List.of(user(1L, "one"), user(2L, "two")));
        ChatService service = new ChatService(
                messages, users, mock(IGroupReadStatusRepository.class), mock(GroupService.class),
                mock(SimpMessagingTemplate.class), mock(FriendService.class),
                mock(PresenceService.class), mongo, access, mock(PushNotificationService.class));

        var page = service.getMessagesByCursor(1L, 2L, null, 2);

        assertTrue(page.hasMore());
        assertEquals("m2", page.nextCursor());
        assertEquals(List.of("m2", "m3"), page.items().stream().map(r -> r.getId()).toList());
        verify(access).requireProfile(1L, 2L);
        verify(users, times(1)).findAllById(org.mockito.ArgumentMatchers.<Iterable<Long>>any());
    }

    @Test
    void directMessageUsesLoadedSenderAndDeliversToBothUsers() {
        IChatMessageRepository messages = mock(IChatMessageRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ContentAccessService access = mock(ContentAccessService.class);
        User sender = user(1L, "sender");
        User receiver = user(2L, "receiver");
        when(users.findById(1L)).thenReturn(Optional.of(sender));
        when(users.findById(2L)).thenReturn(Optional.of(receiver));
        when(messages.save(any(ChatMessage.class)))
                .thenAnswer(
                        invocation -> {
                            ChatMessage message = invocation.getArgument(0);
                            message.setId("message-1");
                            return message;
                        });
        PushNotificationService push = mock(PushNotificationService.class);
        ChatService service =
                new ChatService(
                        messages,
                        users,
                        mock(IGroupReadStatusRepository.class),
                        mock(GroupService.class),
                        messaging,
                        mock(FriendService.class),
                        mock(PresenceService.class),
                        mock(MongoTemplate.class),
                        access,
                        push);

        var response = service.sendMessage(1L, 2L, "  xin chào  ", "client-1");

        assertEquals("xin chào", response.getContent());
        assertEquals("client-1", response.getClientId());
        verify(access).requireProfile(1L, 2L);
        verify(users, times(2)).findById(anyLong());
        verify(messaging)
                .convertAndSendToUser(eq("receiver"), eq("/queue/messages"), same(response));
        verify(messaging).convertAndSendToUser(eq("sender"), eq("/queue/messages"), same(response));
        verify(push).sendChatMessageToUser(2L, "sender", "xin chào", "/chat?userId=1");
    }

    private static ChatMessage chat(String id, String senderId, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(id);
        message.setSenderId(senderId);
        message.setReceiverId("1".equals(senderId) ? "2" : "1");
        message.setConversationId("1_2");
        message.setContent(content);
        return message;
    }

    @Test
    void groupMessagePublishesRealtimeAndQueuesPushForOtherMembers() {
        IChatMessageRepository messages = mock(IChatMessageRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ContentAccessService access = mock(ContentAccessService.class);
        GroupService groups = mock(GroupService.class);
        PushNotificationService push = mock(PushNotificationService.class);
        User sender = user(1L, "sender");
        User receiver = user(2L, "receiver");
        when(groups.isMember(10L, 1L)).thenReturn(true);
        when(groups.getMemberIds(10L)).thenReturn(List.of(1L, 2L));
        GroupResponse group = new GroupResponse();
        group.setId(10L);
        group.setName("Nhóm test");
        when(groups.getGroupDetail(10L, 1L)).thenReturn(group);
        when(users.findById(1L)).thenReturn(Optional.of(sender));
        when(users.findAllById(org.mockito.ArgumentMatchers.<Iterable<Long>>any()))
                .thenReturn(List.of(sender, receiver));
        when(messages.save(any(ChatMessage.class)))
                .thenAnswer(invocation -> {
                    ChatMessage message = invocation.getArgument(0);
                    message.setId("group-message-1");
                    return message;
                });
        ChatService service = new ChatService(
                messages,
                users,
                mock(IGroupReadStatusRepository.class),
                groups,
                messaging,
                mock(FriendService.class),
                mock(PresenceService.class),
                mock(MongoTemplate.class),
                access,
                push);

        var response = service.sendGroupMessage(1L, 10L, "  xin chào nhóm  ", "client-group-1");

        assertEquals("xin chào nhóm", response.getContent());
        verify(messaging).convertAndSendToUser(eq("sender"), eq("/queue/messages"), same(response));
        verify(messaging).convertAndSendToUser(eq("receiver"), eq("/queue/messages"), same(response));
        verify(push).sendChatMessageToUser(2L, "Tin nhắn trong Nhóm test", "sender: xin chào nhóm", "/chat?groupId=10");
        verify(push, never()).sendChatMessageToUser(eq(1L), anyString(), anyString(), anyString());
    }

    @Test
    void deletingChatMessageForEveryoneQueuesMediaCleanupAfterMessageDelete() {
        IChatMessageRepository messages = mock(IChatMessageRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        GroupService groups = mock(GroupService.class);
        MediaAssetLifecycleService mediaAssets = mock(MediaAssetLifecycleService.class);
        ChatMessage message = new ChatMessage();
        message.setId("message-with-media");
        message.setSenderId("1");
        message.setReceiverId("2");
        message.setMediaUrl("https://res.cloudinary.com/demo/image/upload/v1/aloute/chat/photo.png");
        when(messages.findById("message-with-media")).thenReturn(Optional.of(message));
        when(users.findAllById(org.mockito.ArgumentMatchers.<Iterable<Long>>any()))
                .thenReturn(List.of(user(1L, "sender"), user(2L, "receiver")));

        ChatService service = new ChatService(
                messages,
                users,
                mock(IGroupReadStatusRepository.class),
                groups,
                messaging,
                mock(FriendService.class),
                mock(MongoTemplate.class),
                mock(ContentAccessService.class),
                mock(PushNotificationService.class),
                mock(ConversationService.class),
                mock(ChatMessageMapper.class),
                mock(ChatHistoryService.class),
                mock(MediaUploadModerationService.class),
                mediaAssets);

        service.deleteMessage("message-with-media", 1L, true);

        verify(messages).delete(message);
        verify(mediaAssets).queueUnreferencedDeletion(message.getMediaUrl());
    }

    @Test
    void duplicateClientIdReturnsCanonicalMessageWithoutSavingOrBroadcastingAgain() {
        IChatMessageRepository messages = mock(IChatMessageRepository.class);
        IUserRepository users = mock(IUserRepository.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        User sender = user(1L, "sender");
        when(users.findById(1L)).thenReturn(Optional.of(sender));
        ChatMessage persisted = new ChatMessage();
        persisted.setId("message-original");
        persisted.setSenderId("1");
        persisted.setReceiverId("2");
        persisted.setClientId("client-stable");
        persisted.setContent("original body");
        when(messages.findBySenderIdAndClientId("1", "client-stable"))
                .thenReturn(Optional.of(persisted));

        ChatService service = new ChatService(
                messages,
                users,
                mock(IGroupReadStatusRepository.class),
                mock(GroupService.class),
                messaging,
                mock(FriendService.class),
                mock(PresenceService.class),
                mock(MongoTemplate.class),
                mock(ContentAccessService.class),
                mock(PushNotificationService.class));

        var response = service.sendMessage(1L, 2L, "different retry body", "client-stable");

        assertEquals("message-original", response.getId());
        assertEquals("original body", response.getContent());
        assertEquals("client-stable", response.getClientId());
        verify(messages, never()).save(any(ChatMessage.class));
        verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any());
    }

    @Test
    void markingGroupReadAdvancesExistingCursorWithoutSelectingStatusFirst() {
        IGroupReadStatusRepository readStatuses = mock(IGroupReadStatusRepository.class);
        GroupService groups = mock(GroupService.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(groups.isMember(10L, 1L)).thenReturn(true);
        when(readStatuses.updateLastReadAt(
                        eq(10L), eq(1L), any(java.time.LocalDateTime.class)))
                .thenReturn(1);
        when(mongo.find(any(Query.class), eq(ChatMessage.class))).thenReturn(List.of());
        ChatService service =
                new ChatService(
                        mock(IChatMessageRepository.class),
                        mock(IUserRepository.class),
                        readStatuses,
                        groups,
                        mock(SimpMessagingTemplate.class),
                        mock(FriendService.class),
                        mock(PresenceService.class),
                        mongo,
                        mock(ContentAccessService.class),
                        mock(PushNotificationService.class));

        service.markGroupAsRead(10L, 1L);

        verify(readStatuses)
                .updateLastReadAt(eq(10L), eq(1L), any(java.time.LocalDateTime.class));
        verify(readStatuses, never()).findByGroupIdAndUserId(anyLong(), anyLong());
        verify(readStatuses, never()).save(any());
    }

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setActive(true);
        return user;
    }
}
