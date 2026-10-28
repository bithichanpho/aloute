package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

import com.aloute.common.exception.BadRequestException;
import com.cloudinary.Cloudinary;
import org.junit.jupiter.api.Test;
import java.io.IOException;

class UploadPurposeTest {
    @Test
    void normalizesPurposeInOneCentralPlace() {
        assertEquals(UploadPurpose.POST, UploadPurpose.fromRequest(" POST "));
        assertEquals(UploadPurpose.GENERAL, UploadPurpose.fromRequest(null));
        assertEquals(UploadPurpose.GENERAL, UploadPurpose.fromRequest("  "));
    }

    @Test
    void mapsPurposeToIsolatedCloudinaryFolder() {
        assertEquals("aloute/post", UploadPurpose.POST.cloudinaryFolder());
        assertEquals("aloute/chat", UploadPurpose.CHAT.cloudinaryFolder());
        assertEquals("aloute/community", UploadPurpose.COMMUNITY.cloudinaryFolder());
        assertEquals("aloute/avatar", UploadPurpose.AVATAR.cloudinaryFolder());
    }

    @Test
    void verifiesFolderOwnershipWithoutDuplicatingFolderListsAtCallSites() {
        assertTrue(UploadPurpose.CHAT.ownsPublicId("aloute/chat/message.png"));
        assertTrue(UploadPurpose.COMMUNITY.ownsPublicId("aloute/community/cover.png"));
        assertFalse(UploadPurpose.CHAT.ownsPublicId("aloute/post/message.png"));
        assertFalse(UploadPurpose.COMMUNITY.ownsPublicId("aloute/community-old/cover.png"));
    }

    @Test
    void allowsRawFilesOnlyForChatAttachments() {
        assertTrue(UploadPurpose.CHAT.allows(UploadContentValidator.DetectedType.FILE));
        assertFalse(UploadPurpose.POST.allows(UploadContentValidator.DetectedType.FILE));
        assertFalse(UploadPurpose.GENERAL.allows(UploadContentValidator.DetectedType.FILE));
        assertFalse(UploadPurpose.AVATAR.allows(UploadContentValidator.DetectedType.VIDEO));
        assertTrue(UploadPurpose.CHAT.allows(UploadContentValidator.DetectedType.VIDEO));
        assertTrue(UploadPurpose.POST.allows(UploadContentValidator.DetectedType.IMAGE));
    }

    @Test
    void acceptsManagedRawDeliveryUrlsOnlyForTheConfiguredCloud() {
        ImageStorageService storage = new ImageStorageService(mock(Cloudinary.class), "actual-cloud");
        var reference = storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/actual-cloud/raw/upload/v1/aloute/chat/report.pdf")
                .orElseThrow();
        assertEquals("aloute/chat/report.pdf", reference.publicId());
        assertEquals("raw", reference.resourceType());
    }

    @Test
    void rejectsUrlsFromAnotherCloudEvenWhenCloudinaryHostAndFolderMatch() {
        ImageStorageService storage = new ImageStorageService(mock(Cloudinary.class), "actual-cloud");
        assertTrue(storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/other-cloud/image/upload/v1/aloute/chat/photo.png").isEmpty());
    }

    @Test
    void rejectsHttpEncodedPathSeparatorsAndAmbiguousDeliveryUrls() {
        ImageStorageService storage = new ImageStorageService(mock(Cloudinary.class), "actual-cloud");
        assertTrue(storage.resolveAlouteMediaReference(
                "http://res.cloudinary.com/actual-cloud/image/upload/v1/aloute/chat/photo.png").isEmpty());
        assertTrue(storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/actual-cloud/image/upload/v1/aloute%2fchat/photo.png").isEmpty());
        assertTrue(storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/actual-cloud.evil/image/upload/v1/aloute/chat/photo.png").isEmpty());
    }

    @Test
    void storageUploadsChatFilesAsRawAndKeepsFilenameAndSize() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        com.cloudinary.Uploader uploader = mock(com.cloudinary.Uploader.class);
        org.mockito.Mockito.when(cloudinary.uploader()).thenReturn(uploader);
        var file = new org.springframework.mock.web.MockMultipartFile(
                "file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});
        org.mockito.Mockito.when(uploader.upload(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(java.util.Map.of(
                        "secure_url", "https://res.cloudinary.com/actual-cloud/raw/upload/v1/aloute/chat/report.pdf",
                        "public_id", "aloute/chat/report.pdf"));
        ImageStorageService storage = new ImageStorageService(cloudinary, "actual-cloud");

        var uploaded = storage.uploadMedia(file, UploadContentValidator.DetectedType.FILE, UploadPurpose.CHAT);

        assertEquals("raw", uploaded.get("resourceType"));
        assertEquals("report.pdf", uploaded.get("name"));
        assertEquals("3", uploaded.get("size"));
        org.mockito.Mockito.verify(uploader).upload(org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.argThat(options ->
                        "raw".equals(options.get("resource_type"))
                                && "aloute/chat".equals(options.get("folder"))
                                && options.get("public_id").toString().endsWith(".pdf")));
    }

    @Test
    void storageLayerRejectsRawOutsideChat() {
        ImageStorageService storage = new ImageStorageService(mock(Cloudinary.class), "actual-cloud");
        assertThrows(IOException.class, () -> storage.uploadMedia(
                null, UploadContentValidator.DetectedType.FILE, UploadPurpose.POST));
        assertThrows(IOException.class, () -> storage.uploadMedia(
                null, UploadContentValidator.DetectedType.VIDEO, UploadPurpose.AVATAR));
    }

    @Test
    void storageLayerRejectsPurposeMissingForDirectImageUpload() {
        ImageStorageService storage = new ImageStorageService(mock(Cloudinary.class), "actual-cloud");
        assertThrows(IOException.class, () -> storage.uploadImageDetails(null, null));
    }

    @Test
    void acceptsOnlyLiteralConfiguredCloudNameAsFirstPathSegment() {
        ImageStorageService storage = new ImageStorageService(mock(Cloudinary.class), "actual-cloud");
        assertDoesNotThrow(() -> storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/actual-cloud/image/upload/v123/aloute/chat/a.png"));
        assertTrue(storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/actual%2dcloud/image/upload/v123/aloute/chat/a.png").isEmpty());
        assertTrue(storage.resolveAlouteMediaReference(
                "https://res.cloudinary.com/actual-cloud/image/upload/v123/aloute/chat/a.png?raw=1").isEmpty());
    }

    @Test
    void rejectsUnknownPurposes() {
        assertThrows(BadRequestException.class, () -> UploadPurpose.fromRequest("../../other"));
    }
}
