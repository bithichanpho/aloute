package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.User;
import com.aloute.repository.IUserRepository;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;

class AuthenticationUserCacheTest {
    @Test
    void invalidatesOneUsernameWithoutEvictingOtherUsers() {
        IUserRepository users = mock(IUserRepository.class);
        User alice = new User(); alice.setUsername("alice");
        User bob = new User(); bob.setUsername("bob");
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(users.findByUsername("bob")).thenReturn(Optional.of(bob));
        AuthenticationUserCache cache = new AuthenticationUserCache(users, Duration.ofSeconds(30));

        cache.findByUsername("alice");
        cache.findByUsername("bob");
        cache.invalidate("alice");
        cache.findByUsername("alice");
        cache.findByUsername("bob");

        verify(users, times(2)).findByUsername("alice");
        verify(users, times(1)).findByUsername("bob");
    }

    @Test
    void findByIdIsServedFromCacheUntilTheTtlOrAnInvalidation() {
        IUserRepository users = mock(IUserRepository.class);
        User bob = new User(); bob.setId(2L); bob.setUsername("bob");
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        AuthenticationUserCache cache = new AuthenticationUserCache(users, Duration.ofSeconds(30));

        assertEquals("bob", cache.findById(2L).orElseThrow().getUsername());
        cache.findById(2L);
        cache.findById(2L);

        verify(users, times(1)).findById(2L);
        assertTrue(cache.findById(null).isEmpty());
    }

    @Test
    void invalidatingAUsernameAlsoEvictsItsIdSnapshotButNotOtherUsers() {
        IUserRepository users = mock(IUserRepository.class);
        User alice = new User(); alice.setId(1L); alice.setUsername("alice");
        User bob = new User(); bob.setId(2L); bob.setUsername("bob");
        when(users.findById(1L)).thenReturn(Optional.of(alice));
        when(users.findById(2L)).thenReturn(Optional.of(bob));
        AuthenticationUserCache cache = new AuthenticationUserCache(users, Duration.ofSeconds(30));

        cache.findById(1L);
        cache.findById(2L);
        cache.invalidate("alice");
        cache.findById(1L);
        cache.findById(2L);

        verify(users, times(2)).findById(1L);
        verify(users, times(1)).findById(2L);
    }
}
