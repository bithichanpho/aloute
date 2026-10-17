package com.aloute.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.aloute.security.AloutePrincipal;
import com.aloute.service.ImageStorageService;
import com.aloute.service.MediaUploadModerationService;
import com.aloute.service.MediaAssetLifecycleService;
import com.aloute.service.MediaUploadRateLimitService;
import com.aloute.service.UploadPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.Map;

class UploadControllerTest {
    private final ImageStorageService storage = mock(ImageStorageService.class);
    private final MediaUploadModerationService moderation = mock(MediaUploadModerationService.class);
    private final MediaAssetLifecycleService lifecycle = mock(MediaAssetLifecycleService.class);
    private final MediaUploadRateLimitService uploadLimits = mock(MediaUploadRateLimitService.class);
    private final UploadController controller = new UploadController(storage, moderation, lifecycle, uploadLimits);
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
    void postPurposeSkipsRequestThreadModerationAndRegistersUploaderForWorkerLifecycle() throws Exception {
        MockMultipartFile file = png();
        Map<String, String> uploaded = Map.of(
                "url", "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-image.png",
                "type", "image", "publicId", "aloute/post/post-image", "resourceType", "image");
        when(storage.uploadImageDetails(file, UploadPurpose.POST)).thenReturn(uploaded);

        assertSame(uploaded, controller.uploadImage(authentication, file, "post").getData());

        verify(uploadLimits).assertAllowed(7L);
        verify(moderation, never()).requireApproved(any(), anyString(), anyString());
        verify(lifecycle).registerUpload(uploaded, UploadPurpose.POST, 7L);
    }

    @Test
    void communityPurposeModeratesBeforeReturningPublicAsset() throws Exception {
        MockMultipartFile file = png();
        Map<String, String> uploaded = Map.of(
                "url", "https://res.cloudinary.com/demo/image/upload/v1/aloute/community/community-image.png",
                "type", "image", "publicId", "aloute/community/community-image", "resourceType", "image");
        when(storage.uploadImageDetails(file, UploadPurpose.COMMUNITY)).thenReturn(uploaded);

        assertSame(uploaded, controller.uploadImage(authentication, file, "community").getData());

        verify(moderation).requireApproved(uploaded, "image", "community");
        verify(lifecycle).registerUpload(uploaded, UploadPurpose.COMMUNITY, 7L);
    }

    @Test
    void chatVideoPurposeModeratesVideoBeforeReturningPublicAsset() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        Map<String, String> uploaded = Map.of(
                "url", "https://res.cloudinary.com/demo/video/upload/v1/aloute/chat/chat-clip.mp4",
                "type", "video", "publicId", "aloute/chat/chat-clip", "resourceType", "video");
        when(storage.uploadMedia(file, com.aloute.service.UploadContentValidator.DetectedType.VIDEO, UploadPurpose.CHAT))
                .thenReturn(uploaded);

        assertSame(uploaded, controller.uploadMedia(authentication, file, "chat").getData());

        verify(moderation).requireApproved(uploaded, "video", "chat");
        verify(lifecycle).registerUpload(uploaded, UploadPurpose.CHAT, 7L);
    }

    @Test
    void omittedPurposeDefaultsToGeneralAndIsStillModerated() throws Exception {
        MockMultipartFile file = png();
        Map<String, String> uploaded = Map.of(
                "url", "https://res.cloudinary.com/demo/image/upload/v1/aloute/general/general-image.png",
                "type", "image", "publicId", "aloute/general/general-image", "resourceType", "image");
        when(storage.uploadImageDetails(file, UploadPurpose.GENERAL)).thenReturn(uploaded);

        assertSame(uploaded, controller.uploadImage(authentication, file, "general").getData());

        verify(moderation).requireApproved(uploaded, "image", "general");
        verify(lifecycle).registerUpload(uploaded, UploadPurpose.GENERAL, 7L);
    }

    @Test
    void uploadRateLimitIsCheckedBeforeCloudinaryIsCalled() throws Exception {
        MockMultipartFile file = png();
        doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "too many"))
                .when(uploadLimits).assertAllowed(7L);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.uploadImage(authentication, file, "post"));
        verifyNoInteractions(storage);
        verify(lifecycle, never()).registerUpload(any(), any(), any());
    }

    private MockMultipartFile png() {
        return new MockMultipartFile("file", "upload.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
    }
}
