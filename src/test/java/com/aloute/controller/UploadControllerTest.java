package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.security.AloutePrincipal;
import com.aloute.service.ImageStorageService;
import com.aloute.service.ModerationService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.Map;

class UploadControllerTest {
    private final ImageStorageService storage = mock(ImageStorageService.class);
    private final ModerationService moderation = mock(ModerationService.class);
    private final UploadController controller = new UploadController(storage, moderation);
    private final Authentication authentication = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        AloutePrincipal principal = mock(AloutePrincipal.class);
        when(principal.getId()).thenReturn(7L);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn(principal);
        ReflectionTestUtils.setField(controller, "maxUploadSize", DataSize.ofMegabytes(20));
    }

    @Test
    void unsafeImageIsDeletedAndRejected() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "unsafe.png", "image/png", new byte[] {1, 2});
        Map<String, String> uploaded =
                Map.of(
                        "url", "https://cdn/image.png",
                        "type", "image",
                        "publicId", "aloute/unsafe",
                        "resourceType", "image");
        when(storage.uploadImageDetails(file)).thenReturn(uploaded);
        when(moderation.isMediaApproved(uploaded.get("url"), "image")).thenReturn(false);

        assertThrows(BadRequestException.class, () -> controller.uploadImage(authentication, file));
        verify(storage).deleteMedia("aloute/unsafe", "image");
    }

    @Test
    void approvedVideoReturnsUploadedMedia() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "clip.mp4", "video/mp4", new byte[] {1, 2});
        Map<String, String> uploaded =
                Map.of(
                        "url", "https://cdn/clip.mp4",
                        "type", "video",
                        "publicId", "aloute/clip",
                        "resourceType", "video");
        when(storage.uploadMedia(file)).thenReturn(uploaded);
        when(moderation.isMediaApproved(uploaded.get("url"), "video")).thenReturn(true);

        assertSame(uploaded, controller.uploadMedia(authentication, file).getData());
        verify(storage, never()).deleteMedia(any(), any());
    }
}
