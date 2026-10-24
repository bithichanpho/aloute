package com.aloute.users;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import com.aloute.users.api.CallParticipants;
import com.aloute.users.infrastructure.JpaCallParticipants;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CallParticipantsTest {
    @Test
    void batchesOwnedLookupAndExposesOnlyExistingSignalingFields() {
        IUserRepository users = mock(IUserRepository.class);
        User user = new User(); user.setId(1L); user.setUsername("caller");
        user.setFullName("Caller"); user.setAvatarUrl("avatar"); user.setPassword("test-only-value");
        when(users.findAllById(any())).thenReturn(List.of(user));
        var api = new JpaCallParticipants(users);
        var result = api.lookup(List.of(1L, 1L, 2L));
        assertEquals(new CallParticipants.Participant(1L, "caller", "Caller", "avatar"), result.get(1L));
        assertEquals(List.of("id", "username", "fullName", "avatarUrl"),
                java.util.Arrays.stream(CallParticipants.Participant.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        verify(users).findAllById(List.of(1L, 2L));
    }

    @Test
    void emptyLookupsSkipSqlAndLargeLookupsHaveBoundedChunks() {
        IUserRepository users = mock(IUserRepository.class);
        var api = new JpaCallParticipants(users);
        assertTrue(api.lookup(List.of()).isEmpty());
        verifyNoInteractions(users);
        api.lookup(LongStream.rangeClosed(1, 501).boxed().toList());
        verify(users).findAllById(LongStream.rangeClosed(1, 500).boxed().toList());
        verify(users).findAllById(List.of(501L));
    }
}
