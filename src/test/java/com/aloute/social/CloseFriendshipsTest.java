package com.aloute.social;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.model.entity.CloseFriend;
import com.aloute.repository.ICloseFriendRepository;
import com.aloute.social.infrastructure.JpaCloseFriendships;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CloseFriendshipsTest {
    final ICloseFriendRepository repository = mock(ICloseFriendRepository.class);
    final JpaCloseFriendships friendships = new JpaCloseFriendships(repository);

    @Test void anonymousEmptyAndSelfLookupsNeverQuery() {
        assertTrue(friendships.acceptedAmong(null,List.of(1L)).isEmpty());
        assertTrue(friendships.acceptedAmong(1L,List.of()).isEmpty());
        assertTrue(friendships.acceptedAmong(1L,List.of(1L)).isEmpty());
        verifyNoInteractions(repository);
    }

    @Test void acceptedOnlyInEitherDirectionWithoutReturningUnrequestedOrUnrelatedAccounts() {
        when(repository.findRelationships(eq(1L),any())).thenReturn(List.of(
                relationship(1,2,CloseFriend.ACCEPTED), relationship(3,1,CloseFriend.ACCEPTED),
                relationship(1,4,CloseFriend.PENDING), relationship(5,1,CloseFriend.ACCEPTED),
                relationship(6,7,CloseFriend.ACCEPTED)));
        assertEquals(Set.of(2L,3L), friendships.acceptedAmong(1L,List.of(2L,3L,4L,6L)));
    }

    @Test void lookupDeduplicatesAndBoundsEveryQueryToFiveHundredCandidates() {
        var ids = new ArrayList<>(LongStream.rangeClosed(2,502).boxed().toList()); ids.add(2L);
        when(repository.findRelationships(eq(1L),any())).thenAnswer(call -> {
            Collection<Long> chunk = call.getArgument(1);
            assertTrue(chunk.size() <= 500);
            return chunk.stream().map(id -> relationship(1,id,CloseFriend.ACCEPTED)).toList();
        });
        assertEquals(501, friendships.acceptedAmong(1L,ids).size());
        verify(repository,times(2)).findRelationships(eq(1L),any());
        verifyNoMoreInteractions(repository);
    }

    static CloseFriend relationship(long from,long to,String status) {
        var relationship = new CloseFriend(from,to); relationship.setStatus(status); return relationship;
    }
}
