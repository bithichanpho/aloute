package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.aloute.dto.response.*;
import com.aloute.model.entity.*;
import com.aloute.notifications.api.Notifications;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.ui.ExtendedModelMap;

class ProfileUsernameRouteTest {
    final PostService posts = mock(PostService.class);
    final UserService users = mock(UserService.class);
    final ViewController controller = new ViewController(posts, users, mock(FriendService.class), mock(Notifications.class),
            mock(ChatService.class), mock(GroupService.class), mock(PresenceService.class), mock(DiscoveryService.class),
            mock(CommunityService.class), mock(SocialFeatureService.class));

    UsernamePasswordAuthenticationToken authentication() {
        var user = new User(); user.setId(1L); user.setUsername("viewer"); user.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    UserProfileDto profile(String username) {
        var profile = new UserProfileDto(); profile.setId(6L); profile.setUsername(username); return profile;
    }

    @Test void usernameRouteRendersWithoutRedirectingToIdAndKeepsNumericUsernameUnambiguous() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        when(posts.getPostsByUserCursor(6L, 1L, null, 20)).thenReturn(new FeedCursorPage(List.of(), "", false));
        for (var name : List.of("alice.name", "123", "edit")) {
            var profile = profile(name); when(users.getProfileByUsername(name, 1L)).thenReturn(profile);
            mvc.perform(get("/people/{username}", name).principal(authentication()))
                    .andExpect(status().isOk()).andExpect(view().name("profile/view"))
                    .andExpect(model().attribute("profileUser", profile));
            verify(users).getProfileByUsername(name, 1L);
        }
        verify(users, never()).searchUsers(anyString(), anyLong());
        mvc.perform(get("/people/alice.name")).andExpect(redirectedUrl("/login"));
    }

    @Test void firstPageAndFragmentOnlySelectOriginalMediaOwnedByProfile() {
        var own = new PostResponse(); own.setId("own"); own.setUserId(6L); own.setImageUrl("/own.jpg");
        var originalReposted = new PostResponse(); originalReposted.setId("foreign"); originalReposted.setUserId(9L); originalReposted.setImageUrl("/foreign.jpg");
        var quote = new PostResponse(); quote.setId("quote"); quote.setUserId(6L); quote.setImageUrl("/quote.jpg");
        quote.setRepostOf(new PostResponse.RepostOrigin());
        when(users.getProfile(6L, 1L)).thenReturn(profile("alice"));
        when(posts.getPostsByUserCursor(6L, 1L, null, 20))
                .thenReturn(new FeedCursorPage(List.of(own, originalReposted, quote), "", false));
        var page = new ExtendedModelMap(); controller.userProfile(authentication(), 6L, page);
        assertEquals(List.of(own), page.get("mediaPosts"));
        var fragment = new ExtendedModelMap(); controller.profileFragment(authentication(), fragment,
                new org.springframework.mock.web.MockHttpServletResponse(), 6L, null);
        assertEquals(List.of(own), fragment.get("mediaPosts"));
        verify(posts, times(2)).getPostsByUserCursor(6L, 1L, null, 20);
    }
}
