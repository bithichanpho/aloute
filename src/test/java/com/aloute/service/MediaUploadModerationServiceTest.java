package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

class MediaUploadModerationServiceTest {
    private final ModerationService moderation = mock(ModerationService.class);
    private final ImageStorageService storage = mock(ImageStorageService.class);
    private final MediaAssetLifecycleService mediaAssets = mock(MediaAssetLifecycleService.class);
    private final MediaUploadModerationService service =
            new MediaUploadModerationService(moderation, storage, mediaAssets);
    private final Map<String, String> uploaded = Map.of(
            "url", "https://res.cloudinary.com/aloute/image/upload/v1/aloute/avatar/example.png",
            "publicId", "aloute/avatar/example", "resourceType", "image", "type", "image");

    @Test
    void purposeValidationDelegatesToTheSingleLifecycleOwner() {
        service.requirePurposeUrl(uploaded.get("url"), UploadPurpose.AVATAR, "9", "image");
        verify(mediaAssets).requirePurposeUrl(uploaded.get("url"), UploadPurpose.AVATAR, "9", "image");
    }

    @Test
    void rejectedImageIsSentToManagedCleanupBeforeReturningFailure() throws Exception {
        when(moderation.isEnabled()).thenReturn(true);
        when(moderation.isMediaApprovedForUpload(uploaded.get("url"), "image")).thenReturn(false);

        assertThrows(BadRequestException.class, () -> service.requireApproved(uploaded, "image", "avatar"));

        verify(mediaAssets).deleteRejectedUpload(uploaded);
        verify(storage, never()).deleteMedia(anyString(), anyString());
    }

    @Test
    void disabledModerationFailsClosedAndCleansTheUploadedAsset() {
        when(moderation.isEnabled()).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.requireApproved(uploaded, "image", "community"));

        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        verify(mediaAssets).deleteRejectedUpload(uploaded);
    }

    @Test
    void moderationUnavailableFailsClosedAndQueuesCleanup() {
        when(moderation.isEnabled()).thenReturn(true);
        when(moderation.isMediaApprovedForUpload(uploaded.get("url"), "image"))
                .thenThrow(new IllegalStateException("moderation unavailable"));

        assertThrows(IllegalStateException.class,
                () -> service.requireApproved(uploaded, "image", "community"));

        verify(mediaAssets).deleteRejectedUpload(uploaded);
    }

    @Test
    void rejectedCleanupStillHasOnlyOneLifecycleOwnerWhenItFails() {
        doThrow(new IllegalStateException("PostgreSQL unavailable"))
                .when(mediaAssets).deleteRejectedUpload(uploaded);

        service.deleteQuietly(uploaded);

        verify(mediaAssets).deleteRejectedUpload(uploaded);
        verifyNoInteractions(storage);
    }
}
