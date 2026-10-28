package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.dto.response.FollowRequestResponse;
import com.aloute.dto.response.SocialCursorPage;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.Notifications;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;

class FollowRequestInboxTest {
    private final SocialFeatureService social = mock(SocialFeatureService.class);
    private final ViewController controller = new ViewController(mock(PostService.class), mock(UserService.class),
            mock(FriendService.class), mock(Notifications.class), mock(ChatService.class), mock(GroupService.class),
            mock(PresenceService.class), mock(DiscoveryService.class), mock(CommunityService.class), social);

    private UsernamePasswordAuthenticationToken authentication() {
        var user = new User(); user.setId(1L); user.setUsername("alice"); user.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void openingInboxAcknowledgesItBeforeLoadingPendingRequestsAndUnreadBadge() {
        var pending = new FollowRequestResponse("6:1", 6L, 1L, Instant.now(), "bob", "Bob", null);
        when(social.requests(1L, null, 50)).thenReturn(new SocialCursorPage<>(List.of(pending), "next", true));
        var model = new ExtendedModelMap();
        assertEquals("follow-requests/index", controller.followRequests(authentication(), model));
        assertEquals(List.of(pending), model.get("followRequests"));
        assertEquals(0L, model.get("followRequestCount"));
        assertEquals(true, model.get("requestsHasMore"));
        var ordered = inOrder(social);
        ordered.verify(social).markRequestsSeen(1L);
        ordered.verify(social).requests(1L, null, 50);
        ordered.verify(social).countUnreadRequests(1L);
        verifyNoMoreInteractions(social);
    }

    @Test
    void anonymousPageVisitCannotAcknowledgeAnyone() {
        assertEquals("redirect:/login", controller.followRequests(null, new ExtendedModelMap()));
        verifyNoInteractions(social);
    }

    @Test
    void unreadEndpointUsesPrincipalAndDoesNotCountAllPendingRequests() throws Exception {
        when(social.countUnreadRequests(1L)).thenReturn(2L);
        var mvc = MockMvcBuilders.standaloneSetup(new SocialFeatureController(social)).build();
        mvc.perform(get("/api/follow-requests/unread-count?userId=99").principal(authentication()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(2));
        verify(social).countUnreadRequests(1L);
        verifyNoMoreInteractions(social);
    }

    @Test
    void anonymousUnreadEndpointCannotQueryAnInbox() {
        assertThrows(UnauthorizedException.class,
                () -> new SocialFeatureController(social).pendingFollowRequestCount(null));
        verifyNoInteractions(social);
    }
}
