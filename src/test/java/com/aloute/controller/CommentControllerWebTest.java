package com.aloute.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aloute.dto.response.CommentCursorPage;
import com.aloute.dto.response.CommentResponse;
import com.aloute.model.entity.Role;
import com.aloute.model.entity.User;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.CommentService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

class CommentControllerWebTest {
    private CommentService comments;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        comments = org.mockito.Mockito.mock(CommentService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CommentController(comments)).build();
    }

    @Test
    void repliesEndpointReturnsCursorPageForAuthenticatedViewer() throws Exception {
        User user = new User();
        user.setId(21L);
        user.setUsername("viewer");
        user.setPassword("unused");
        user.setFullName("Viewer");
        user.setRole(new Role("ROLE_USER"));
        AloutePrincipal principal = new AloutePrincipal(user);
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());

        CommentResponse reply = new CommentResponse();
        reply.setId("reply-1");
        reply.setContent("Hello");
        CommentCursorPage page = new CommentCursorPage(List.of(reply), "cursor-next", true);
        when(comments.getReplies("parent-1", 21L, null, 20)).thenReturn(page);

        mvc.perform(get("/api/comments/parent-1/replies")
                        .principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].id").value("reply-1"))
                .andExpect(jsonPath("$.data.nextCursor").value("cursor-next"))
                .andExpect(jsonPath("$.data.hasMore").value(true));

        verify(comments).getReplies("parent-1", 21L, null, 20);
    }
}
