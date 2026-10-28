package com.aloute.users;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.repository.IUserRepository;
import com.aloute.users.infrastructure.JpaUserDirectory;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class UserDirectoryTest {
    @Test
    void profileLabelsUsePublicProjectionAndBoundQueriesWithoutCredentials() {
        var users=mock(IUserRepository.class); var entry=mock(IUserRepository.ProfileLabelEntry.class);
        when(entry.getId()).thenReturn(1L); when(entry.getUsername()).thenReturn("alice"); when(entry.getFullName()).thenReturn("Alice");
        when(users.findProfileLabels(anyCollection())).thenReturn(List.of(entry));
        var directory=new JpaUserDirectory(users);
        var ids=new java.util.ArrayList<>(LongStream.rangeClosed(1,1001).boxed().toList()); ids.add(1L); ids.add(null);
        var labels=directory.profileLabels(ids);
        assertEquals("Alice",labels.get(1L).fullName()); assertEquals("/images/avatar_default.png",labels.get(1L).avatarUrl());
        assertThrows(UnsupportedOperationException.class,labels::clear);
        verify(users,times(3)).findProfileLabels(argThat(batch->batch.size()<=500)); verifyNoMoreInteractions(users);
    }

    @Test
    void lookupUsesScalarProjectionInsteadOfLoadingCredentials() {
        IUserRepository users = mock(IUserRepository.class);
        when(users.findDirectoryUsername(1L)).thenReturn(Optional.of("alice"));
        JpaUserDirectory directory = new JpaUserDirectory(users);
        assertEquals(Optional.of("alice"), directory.username(1L));
        assertTrue(directory.username(null).isEmpty());
        verify(users, never()).findById(any());
    }

    @Test
    void bulkLookupIsImmutableDeduplicatedAndBounded() {
        IUserRepository users = mock(IUserRepository.class);
        IUserRepository.DirectoryEntry entry = mock(IUserRepository.DirectoryEntry.class);
        when(entry.getId()).thenReturn(1L);
        when(entry.getUsername()).thenReturn("alice");
        when(users.findDirectoryEntries(anyCollection())).thenReturn(List.of(entry));
        JpaUserDirectory directory = new JpaUserDirectory(users);
        var ids = new java.util.ArrayList<>(LongStream.rangeClosed(1, 1001).boxed().toList());
        ids.add(1L);
        ids.add(null);

        Map<Long, String> result = directory.usernames(ids);

        assertEquals(Map.of(1L, "alice"), result);
        assertThrows(UnsupportedOperationException.class, () -> result.put(2L, "bob"));
        verify(users, times(3)).findDirectoryEntries(argThat(batch -> batch.size() <= 500));
        verify(users, never()).findAllById(any());
        clearInvocations(users);
        assertTrue(directory.usernames(List.of()).isEmpty());
        verifyNoInteractions(users);
    }
}
