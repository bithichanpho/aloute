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
                        mock(IGroupReadStatusRepository.class),
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
        when(members.findByGroupId(eq(9L), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(memberships);
        when(members.countByGroupId(9L)).thenReturn(100L);
        when(users.findAllById(any())).thenReturn(accounts);
        var result = service.getGroupDetail(9L, 1L);
        assertEquals(100, result.getMembers().size());
        assertEquals("user1", result.getMembers().getFirst().getUsername());
        assertEquals("ADMIN", result.getMembers().getFirst().getRole());
        assertNull(result.getMembers().getLast().getUsername());
        assertEquals(100, result.getMemberCount());
        verify(members).findByGroupId(eq(9L), argThat(page -> page.getPageSize() == 100));
        verify(users).findAllById(any());
        verify(users, never()).findById(any());
    }

    @Test
    void addingMemberCreatesUnreadBaselineAtJoinTime() {
        var groups = mock(IChatGroupRepository.class);
        var members = mock(IGroupMemberRepository.class);
        var readStatuses = mock(IGroupReadStatusRepository.class);
        var users = mock(IUserRepository.class);
        var friends = mock(FriendService.class);
        var group = new ChatGroup();
        group.setId(9L);
        var admin = new GroupMember(9L, 1L, "ADMIN");
        when(groups.findById(9L)).thenReturn(Optional.of(group));
        when(members.findByGroupIdAndUserId(9L, 1L)).thenReturn(Optional.of(admin));
        when(users.existsById(2L)).thenReturn(true);
        when(members.existsByGroupIdAndUserId(9L, 2L)).thenReturn(false);
        when(members.countByGroupId(9L)).thenReturn(1L);
        when(friends.isFriend(1L, 2L)).thenReturn(true);
        when(members.findByGroupId(9L)).thenReturn(List.of(admin));
        when(users.findAllById(any())).thenReturn(List.of());
        var service =
                new GroupService(
                        groups,
                        members,
                        readStatuses,
                        users,
                        friends,
                        mock(SimpMessagingTemplate.class));

        service.addMember(9L, 1L, 2L);

        var status = org.mockito.ArgumentCaptor.forClass(GroupReadStatus.class);
        verify(readStatuses).save(status.capture());
        assertEquals(9L, status.getValue().getGroupId());
        assertEquals(2L, status.getValue().getUserId());
        assertNotNull(status.getValue().getLastReadAt());
    }
}
