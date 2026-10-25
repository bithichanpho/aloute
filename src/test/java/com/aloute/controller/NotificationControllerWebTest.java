package com.aloute.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.aloute.common.exception.GlobalExceptionHandler;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.dto.response.NotificationCursorPage;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.Notifications;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.AuthRateLimitService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class NotificationControllerWebTest {
    private final Notifications notifications = mock(Notifications.class);
    private final AuthRateLimitService limits = mock(AuthRateLimitService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new NotificationController(notifications, limits))
                .setControllerAdvice(new GlobalExceptionHandler(JsonMapper.builder().build())).build();
    }

    @Test
    void cursorEndpointKeepsEnvelopeAndPaginationContract() throws Exception {
        when(notifications.getMyNotifications(7L, 11L, 2))
                .thenReturn(new NotificationCursorPage(List.of(), 8L, true));
        mvc.perform(get("/api/notifications/cursor").principal(authentication())
                        .param("beforeId", "11").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.nextCursor").value(8))
                .andExpect(jsonPath("$.data.hasMore").value(true));
        verify(notifications).getMyNotifications(7L, 11L, 2);
    }

    @Test
    void clientCannotSpoofNotificationOwner() throws Exception {
        mvc.perform(post("/api/notifications/10/read").principal(authentication()).param("userId", "999"))
                .andExpect(status().isOk());
        verify(notifications).markAsRead(7L, 10L);
        verify(notifications, never()).markAsRead(999L, 10L);
    }

    @Test
    void foreignNotificationRemainsNotFoundAndAnonymousRequestsRemainUnauthorized() throws Exception {
        doThrow(new ResourceNotFoundException("Không tìm thấy thông báo"))
                .when(notifications).delete(7L, 10L);
        mvc.perform(delete("/api/notifications/10").principal(authentication())).andExpect(status().isNotFound());
        clearInvocations(notifications);
        mvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
    }

    private UsernamePasswordAuthenticationToken authentication() {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setRole(new Role("ROLE_USER"));
        AloutePrincipal principal = new AloutePrincipal(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
