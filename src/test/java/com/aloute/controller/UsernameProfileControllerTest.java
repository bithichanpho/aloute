package com.aloute.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.aloute.common.exception.*;
import com.aloute.dto.response.*;
import com.aloute.model.entity.*;
import com.aloute.notifications.api.Notifications;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class UsernameProfileControllerTest {
    final UserService users = mock(UserService.class);
    final PostService posts = mock(PostService.class);
    MockMvc mvc; UsernamePasswordAuthenticationToken authentication;
    @BeforeEach void setup() {
        var controller = new ViewController(posts, users, mock(FriendService.class), mock(Notifications.class),
                mock(ChatService.class), mock(GroupService.class), mock(PresenceService.class), mock(DiscoveryService.class),
                mock(CommunityService.class), mock(SocialFeatureService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(tools.jackson.databind.json.JsonMapper.builder().build())).build();
        var viewer = new User(); viewer.setId(2L); viewer.setUsername("viewer"); viewer.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(viewer);
        authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test void usernameRouteUsesResolvedIdAndAuthenticatedViewerForContentAndCounts() throws Exception {
        when(users.getProfileByUsername("CaSo", 2L)).thenReturn(profile(26L, "caso"));
        when(posts.getPostsByUserCursor(26L, 2L, null, 20)).thenReturn(new FeedCursorPage(List.of(), "", false));
        when(posts.countPostsByUser(26L)).thenReturn(7L);
        mvc.perform(get("/@CaSo").principal(authentication).param("currentUserId", "26"))
                .andExpect(status().isOk()).andExpect(view().name("profile/view"))
                .andExpect(model().attribute("postCount", 7L)).andExpect(model().attribute("userPosts", List.of()))
                .andExpect(model().attribute("isOwnProfile", false));
        verify(posts).getPostsByUserCursor(26L, 2L, null, 20);
        verify(users, never()).getProfile(26L, 26L);
    }

    @Test void numericUsernameIsNotConfusedWithDatabaseId() throws Exception {
        when(users.getProfileByUsername("26", 2L)).thenReturn(profile(77L, "26"));
        when(posts.getPostsByUserCursor(77L, 2L, null, 20)).thenReturn(new FeedCursorPage(List.of(), "", false));
        mvc.perform(get("/@26").principal(authentication)).andExpect(status().isOk());
        verify(posts).getPostsByUserCursor(77L, 2L, null, 20); verify(users, never()).getProfile(26L, 2L);
    }

    @Test void idMeAndMentionAliasesRedirectToTheSameCanonicalProfile() throws Exception {
        when(users.getProfile(26L, 2L)).thenReturn(profile(26L, "caso"));
        when(users.getProfile(2L, 2L)).thenReturn(profile(2L, "viewer"));
        when(users.getProfileByUsername("caso", 2L)).thenReturn(profile(26L, "caso"));
        mvc.perform(get("/profile/26").principal(authentication)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/@caso"));
        mvc.perform(get("/profile/me").principal(authentication)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/@viewer"));
        mvc.perform(get("/people/caso").principal(authentication)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/@caso"));
        verifyNoInteractions(posts);
    }

    @Test void unknownAndDeniedProfilesNeverLoadContent() throws Exception {
        when(users.getProfileByUsername("missing", 2L)).thenThrow(new ResourceNotFoundException("missing"));
        when(users.getProfileByUsername("blocked", 2L)).thenThrow(new ResourceNotFoundException("not available"));
        mvc.perform(get("/@missing").principal(authentication).accept("text/html")).andExpect(status().isNotFound());
        mvc.perform(get("/@blocked").principal(authentication).accept("text/html")).andExpect(status().isNotFound());
        verifyNoInteractions(posts);
    }

    @Test void unauthenticatedNewAndLegacyRoutesKeepLoginRequirement() throws Exception {
        for (String path : List.of("/@caso", "/profile/26", "/profile/me", "/people/caso"))
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        verifyNoInteractions(users, posts);
    }

    @Test void profileEditRouteStillWinsOverLegacyIdMapping() throws Exception {
        mvc.perform(get("/profile/edit").principal(authentication)).andExpect(status().isOk()).andExpect(view().name("profile/edit"));
        verify(users).getProfile(2L, 2L); verifyNoInteractions(posts);
    }

    private UserProfileDto profile(Long id, String username) {
        var profile = new UserProfileDto(); profile.setId(id); profile.setUsername(username); profile.setPrivateAccount(true); return profile;
    }
}
