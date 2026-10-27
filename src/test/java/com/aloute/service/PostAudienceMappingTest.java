package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.document.*;
import com.aloute.model.entity.User;
import com.aloute.repository.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PostAudienceMappingTest {
    final IPostRepository posts = mock(IPostRepository.class);
    final IUserRepository users = mock(IUserRepository.class);
    final ContentAccessService access = mock(ContentAccessService.class);
    final PostResponseMapper mapper = new PostResponseMapper(posts,users,mock(ILikeRepository.class),
            mock(IRepostRepository.class),access,mock(RepostPresentationService.class));

    Post post(String id, String author, PostVisibility audience) {
        var post = new Post(); post.setId(id); post.setUserId(author); post.setVisibility(audience);
        post.setCreatedAt(java.time.LocalDateTime.now()); return post;
    }

    @Test void repostOriginRetainsItsOwnAudienceInSingleAndBatchedMapping() {
        var outer = post("quote","1",PostVisibility.PUBLIC);
        outer.setRepostOfId("original"); outer.setContent("caption");
        var original = post("original","2",PostVisibility.FRIENDS);
        when(posts.findById("original")).thenReturn(Optional.of(original));
        var permissions = new ContentAccessService.AccessBatch(1L,Map.of(),Set.of(),Set.of(2L),Set.of(2L),Map.of("original",original),Set.of());
        var scalar = mapper.toResponse(outer,1L);
        var batched = mapper.toReadableResponses(List.of(outer),1L,permissions).getFirst();
        for (var response : List.of(scalar,batched)) {
            assertEquals(PostVisibility.PUBLIC,response.getVisibility());
            assertEquals(PostVisibility.FRIENDS,response.getRepostOf().getVisibility());
        }
        clearInvocations(posts,users);
        mapper.toReadableResponses(List.of(outer),1L,permissions);
        verifyNoInteractions(posts,users); // Audience metadata adds no per-card queries.
    }

    @Test void retryAvailabilityIsOwnerOnlyAndNeverGrantedForContentFlags() {
        var own = post("p","1",PostVisibility.PUBLIC); own.setModerationStatus(ModerationStatus.FLAGGED);
        own.setModerationFailureReason("TEXT_SERVICE_UNAVAILABLE");
        var permissions = new ContentAccessService.AccessBatch(1L,Map.of(),Set.of(),Set.of(),Set.of(),Map.of(),Set.of());
        assertTrue(mapper.toResponse(own,1L).isModerationRetryAvailable());
        assertTrue(mapper.toReadableResponses(List.of(own),1L,permissions).getFirst().isModerationRetryAvailable());
        assertFalse(mapper.toResponse(own,2L).isModerationRetryAvailable());
        assertFalse(mapper.toReadableResponses(List.of(own),2L,permissions).getFirst().isModerationRetryAvailable());
        own.setModerationFailureReason(null);
        assertFalse(mapper.toResponse(own,1L).isModerationRetryAvailable());
    }
}
