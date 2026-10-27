package com.aloute.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aloute.common.exception.GlobalExceptionHandler;
import com.aloute.dto.response.FollowerRemovalResponse;
import com.aloute.model.entity.*;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.*;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.csrf.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FollowerRemovalControllerTest {
    FriendService friends = mock(FriendService.class);
    MockMvc mvc;
    UsernamePasswordAuthenticationToken authentication;
    @BeforeEach void setup() {
        var csrf = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        mvc = MockMvcBuilders.standaloneSetup(new FriendController(friends, mock(UserService.class), mock(AuthRateLimitService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(tools.jackson.databind.json.JsonMapper.builder().build()))
                .addFilters(csrf).build();
        var user = new User(); user.setId(1L); user.setUsername("owner"); user.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(user);
        authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test void authenticatedOwnerCanRemoveFollowerAndGetsStableEnvelope() throws Exception {
        when(friends.removeFollower(1L, 2L)).thenReturn(new FollowerRemovalResponse(true, 8));
        mvc.perform(delete("/api/users/1/followers/2").principal(authentication)
                        .cookie(new Cookie("XSRF-TOKEN", "fixture-csrf")).header("X-XSRF-TOKEN", "fixture-csrf")
                        .param("currentUserId", "99").param("ownerId", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.removed").value(true)).andExpect(jsonPath("$.data.followerCount").value(8));
        verify(friends).removeFollower(1L, 2L); verifyNoMoreInteractions(friends);
    }

    @Test void cannotRemoveAnotherAccountsFollowersByForgingOwner() throws Exception {
        mvc.perform(delete("/api/users/3/followers/2").principal(authentication)
                        .cookie(new Cookie("XSRF-TOKEN", "fixture-csrf")).header("X-XSRF-TOKEN", "fixture-csrf")
                        .param("currentUserId", "3"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(friends);
    }

    @Test void anonymousCannotRemoveEvenWithCsrfToken() throws Exception {
        mvc.perform(delete("/api/users/1/followers/2")
                        .cookie(new Cookie("XSRF-TOKEN", "fixture-csrf")).header("X-XSRF-TOKEN", "fixture-csrf"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(friends);
    }

    @Test void missingOrWrongCsrfCannotRemove() throws Exception {
        mvc.perform(delete("/api/users/1/followers/2").principal(authentication)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/users/1/followers/2").principal(authentication)
                        .cookie(new Cookie("XSRF-TOKEN", "fixture-csrf")).header("X-XSRF-TOKEN", "wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(friends);
    }
}
