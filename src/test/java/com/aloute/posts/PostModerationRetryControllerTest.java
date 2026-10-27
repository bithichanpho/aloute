package com.aloute.posts;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.aloute.model.entity.*;
import com.aloute.posts.application.PostModerationRetryService;
import com.aloute.posts.web.PostModerationRetryController;
import com.aloute.security.AloutePrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PostModerationRetryControllerTest {
    @Test void retryUsesAuthenticatedActorAndKeepsSuccessEnvelope() throws Exception {
        var service = mock(PostModerationRetryService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PostModerationRetryController(service)).build();
        var user = new User(); user.setId(7L); user.setUsername("viewer"); user.setRole(new Role("ROLE_USER"));
        var principal = new AloutePrincipal(user);
        var authentication = new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities());
        mvc.perform(post("/api/posts/p/moderation-retry").principal(authentication).param("userId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).retry(7L,"p"); verifyNoMoreInteractions(service);
    }
}
