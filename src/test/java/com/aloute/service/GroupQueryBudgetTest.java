package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.*;
import com.aloute.repository.*;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;
import java.util.stream.IntStream;

class GroupQueryBudgetTest {
    @Test
    void groupDetailLoadsOneHundredMembersInOneUserQuery() {
        var groups = mock(IChatGroupRepository.class);
        var members = mock(IGroupMemberRepository.class);
        var users = mock(IUserRepository.class);
        var service =
                new GroupService(
                        groups,
                        members,
                        users,
                        mock(FriendService.class),
                        mock(SimpMessagingTemplate.class));
        var group = new ChatGroup();
        group.setId(9L);
        var memberships =
                IntStream.rangeClosed(1, 100)
                        .mapToObj(
                                id -> new GroupMember(9L, (long) id, id == 1 ? "ADMIN" : "MEMBER"))
                        .toList();
        var accounts =
                IntStream.rangeClosed(1, 99)
                        .mapToObj(
                                id -> {
                                    var user = new User();
                                    user.setId((long) id);
                                    user.setUsername("user" + id);
                                    return user;
                                })
                        .toList();
        when(groups.findById(9L)).thenReturn(Optional.of(group));
        when(members.findByGroupIdAndUserId(9L, 1L))
                .thenReturn(Optional.of(memberships.getFirst()));
        when(members.findByGroupId(9L)).thenReturn(memberships);
        when(users.findAllById(any())).thenReturn(accounts);
        var result = service.getGroupDetail(9L, 1L);
        assertEquals(100, result.getMembers().size());
        assertEquals("user1", result.getMembers().getFirst().getUsername());
        assertEquals("ADMIN", result.getMembers().getFirst().getRole());
        assertNull(result.getMembers().getLast().getUsername());
        verify(users).findAllById(any());
        verify(users, never()).findById(any());
    }
}
