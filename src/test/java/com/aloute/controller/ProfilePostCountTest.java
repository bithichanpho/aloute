package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.aloute.dto.response.*;
import com.aloute.model.entity.*;
import com.aloute.notifications.api.Notifications;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.ui.ExtendedModelMap;

class ProfilePostCountTest {
    @Test void profileTotalDoesNotUseVisiblePageSizeForOwnerFollowerOrOutsider() {
        var posts = mock(PostService.class); var users = mock(UserService.class);
        var controller = new ViewController(posts, users, mock(FriendService.class), mock(Notifications.class),
                mock(ChatService.class), mock(GroupService.class), mock(PresenceService.class),
                mock(DiscoveryService.class), mock(CommunityService.class), mock(SocialFeatureService.class));
        when(posts.countPostsByUser(1L)).thenReturn(105L);
        when(users.getProfile(anyLong(), anyLong())).thenAnswer(call -> {
            var profile = new UserProfileDto(); profile.setId(call.getArgument(0));
            profile.setPrivateAccount(true); profile.setFollowedByCurrentUser(Long.valueOf(2L).equals(call.getArgument(1)));
            return profile;
        });
        for (long viewer : List.of(1L, 2L, 3L)) {
            var user = new User(); user.setId(viewer); user.setUsername("viewer" + viewer); user.setRole(new Role("ROLE_USER"));
            var principal = new AloutePrincipal(user);
            var authentication = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
            var visible = viewer == 3L ? List.<PostResponse>of() : List.of(new PostResponse());
            when(posts.getPostsByUserCursor(1L, viewer, null, 20)).thenReturn(new FeedCursorPage(visible, "next", viewer != 3L));
            var model = new ExtendedModelMap();
            assertEquals("profile/view", controller.userProfile(authentication, 1L, model));
            assertEquals(105L, model.get("postCount"));
            assertEquals(visible, model.get("userPosts"));
        }
        verify(posts, times(3)).countPostsByUser(1L);
    }
}
