package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aloute.dto.request.EditPostRequest;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.SocialFeatureService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PostEditContractTest {
    @Test
    void captionOnlyAndLegacyMediaBodiesKeepUrlMethodAndPresenceSemantics() throws Exception {
        var service = mock(SocialFeatureService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new SocialFeatureController(service)).build();
        var auth = mock(Authentication.class);
        var principal = mock(AloutePrincipal.class);
        when(auth.isAuthenticated()).thenReturn(true); when(auth.getPrincipal()).thenReturn(principal);
        when(principal.getId()).thenReturn(1L);
        mvc.perform(put("/api/posts/p").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"caption\",\"visibility\":\"CLOSE_FRIENDS\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/posts/p").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":null,\"imageUrl\":\"https://res.cloudinary.com/owned.jpg\",\"videoUrl\":null}"))
                .andExpect(status().isOk());
        var body = ArgumentCaptor.forClass(EditPostRequest.class);
        verify(service, times(2)).edit(eq(1L), eq("p"), body.capture());
        var caption = body.getAllValues().getFirst();
        assertTrue(caption.contentProvided()); assertFalse(caption.imageProvided()); assertFalse(caption.videoProvided());
        var legacy = body.getAllValues().getLast();
        assertTrue(legacy.contentProvided()); assertTrue(legacy.imageProvided()); assertTrue(legacy.videoProvided());
        assertNull(legacy.getVideoUrl()); assertEquals("PUBLIC", legacy.getVisibility().name());
        mvc.perform(put("/api/posts/p").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"caption\",\"visibility\":null}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/posts/p").principal(auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"imageUrl\":\"javascript:bad\"}"))
                .andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
}
