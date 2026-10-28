package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.notifications.api.Notifications;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;

class ActivityPageTest {
    @Test
    void activityDoesNotLoadPendingRequestsButKeepsTheirSeparateNavigationCount() {
        var notifications = mock(Notifications.class);
        var social = mock(SocialFeatureService.class);
        var controller = new ViewController(mock(PostService.class), mock(UserService.class),
                mock(FriendService.class), notifications, mock(ChatService.class), mock(GroupService.class),
                mock(PresenceService.class), mock(DiscoveryService.class), mock(CommunityService.class), social);
        var user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(user);
        var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        when(notifications.getMyNotifications(1L)).thenReturn(List.of());
        when(social.countUnreadRequests(1L)).thenReturn(3L);
        var model = new ExtendedModelMap();

        assertEquals("activity/index", controller.activity(authentication, model));
        assertEquals(List.of(), model.get("notifications"));
        assertEquals(3L, model.get("followRequestCount"));
        assertFalse(model.containsAttribute("activityFollowRequests"));
        verify(notifications).markAllAsRead(1L);
        verify(social).countUnreadRequests(1L);
        verifyNoMoreInteractions(social);
    }
}
