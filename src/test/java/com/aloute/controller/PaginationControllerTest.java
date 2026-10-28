package com.aloute.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aloute.security.AloutePrincipal;
import com.aloute.security.ClientIpResolver;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.CommunityService;
import com.aloute.service.DiscoveryService;
import com.aloute.service.PostService;
import com.aloute.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class PaginationControllerTest {
    @Test
    void communityJoinRequestsForwardPageAndSize() {
        CommunityService communities = mock(CommunityService.class);
        CommunityController controller = new CommunityController(
                communities, mock(AuthRateLimitService.class));
        Authentication authentication = authentication(5L);

        controller.pendingMembers(authentication, 9L, 3, 25);

        verify(communities).pendingMembers(9L, 5L, 3, 25);
    }

    @Test
    void searchEndpointsForwardPageAndSize() {
        UserService users = mock(UserService.class);
        PostService posts = mock(PostService.class);
        ClientIpResolver ip = mock(ClientIpResolver.class);
        SearchController controller = new SearchController(
                users, posts, mock(DiscoveryService.class),
                mock(AuthRateLimitService.class), ip);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(ip.resolve(request)).thenReturn("127.0.0.1");
        Authentication authentication = authentication(5L);

        controller.searchUsers(authentication, request, "alice", 2, 15);
        controller.searchPosts(authentication, request, "spring", 4, 10);
        controller.searchPosts(authentication, request, "  #ALOUTE  ", 1, 20);

        verify(users).searchUsersExcludingCurrentUser("alice", 5L, 2, 15);
        verify(posts).searchPosts("spring", 5L, 4, 10);
        verify(posts).searchPosts("#ALOUTE", 5L, 1, 20);
    }

    private Authentication authentication(Long userId) {
        Authentication authentication = mock(Authentication.class);
        AloutePrincipal principal = mock(AloutePrincipal.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getId()).thenReturn(userId);
        return authentication;
    }
}
