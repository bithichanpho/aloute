package com.aloute.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aloute.dto.response.LikeResponse;
import com.aloute.dto.response.FeedCursorPage;
import com.aloute.dto.response.PostResponse;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.AuthRateLimitService;
import com.aloute.service.ChatService;
import com.aloute.service.ContentAccessService;
import com.aloute.service.FriendService;
import com.aloute.service.LikeService;
import com.aloute.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PostControllerWebTest {
    private LikeService likes;
    private AuthRateLimitService limits;
    private FriendService friends;
    private PostService posts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        posts = mock(PostService.class);
        likes = mock(LikeService.class);
        friends = mock(FriendService.class);
        limits = mock(AuthRateLimitService.class);
        ChatService chat = mock(ChatService.class);
        ContentAccessService access = mock(ContentAccessService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new PostController(posts, likes, friends, limits, chat, access, "http://localhost:8080"))
                .build();
    }

    @Test
    void authenticatedUserCanSetLikeAndReceivesUpdatedCount() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("unused");
        user.setFullName("Alice");
        user.setRole(new Role("ROLE_USER"));
        AloutePrincipal principal = new AloutePrincipal(user);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        when(likes.setLike("post-1", 7L, true)).thenReturn(new LikeResponse("post-1", true, 4));

        mvc.perform(post("/api/posts/post-1/like")
                        .principal(authentication)
                        .contentType("application/json")
                        .content("{\"liked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(4));

        verify(limits).checkAndRecord(eq("LIKE"), eq("7"), eq(120), any(), any(), any(), any());
        verify(likes).setLike("post-1", 7L, true);
    }

    @Test
    void cursorFeedEndpointReturnsStableCursorMetadata() throws Exception {
        User user = new User();
        user.setId(7L);
        user.setUsername("alice");
        user.setPassword("unused");
        user.setFullName("Alice");
        user.setRole(new Role("ROLE_USER"));
        AloutePrincipal principal = new AloutePrincipal(user);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        PostResponse item = new PostResponse();
        item.setId("post-a");
        FeedCursorPage page = new FeedCursorPage(java.util.List.of(item), "opaque-next", true);
        when(posts.getCursorFeedForViewer(7L, "foryou", null, null, 20))
                .thenReturn(page);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/posts/feed/cursor")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("post-a"))
                .andExpect(jsonPath("$.data.nextCursor").value("opaque-next"))
                .andExpect(jsonPath("$.data.hasMore").value(true));
    }
}
