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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.List;

class ChatServiceTest {
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
        when(users.findAllById(any(Iterable.class))).thenReturn(List.of(sender, receiver));
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

    private User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setActive(true);
        return user;
    }
}
