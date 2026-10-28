package com.aloute.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.aloute.service.GoogleAvatarService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AvatarControllerTest {
    @Test void successfulImagesAreCachedButTemporaryDefaultsAreNotStoredByBrowser() throws Exception {
        var service = mock(GoogleAvatarService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AvatarController(service)).build();
        String url = "https://lh3.googleusercontent.com/a/example=s96-c";
        byte[] data = {1, 2, 3};
        when(service.get(url)).thenReturn(new GoogleAvatarService.AvatarImage(data, MediaType.IMAGE_PNG, false));
        mvc.perform(get("/images/avatars/google").param("url", url))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(data)).andExpect(header().string("Cache-Control", "max-age=900, public"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        when(service.get(url)).thenReturn(new GoogleAvatarService.AvatarImage(data, MediaType.IMAGE_PNG, true));
        mvc.perform(get("/images/avatars/google").param("url", url))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }
}
