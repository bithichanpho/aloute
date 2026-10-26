package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;

import com.aloute.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class UploadContentValidatorTest {
    private final UploadContentValidator validator = new UploadContentValidator();

    @Test
    void acceptsPngSignatureWhenDeclaredPng() {
        var file = new MockMultipartFile("file", "photo.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        assertEquals(UploadContentValidator.DetectedType.IMAGE, validator.validateImage(file));
    }

    @Test
    void rejectsExecutableBytesClaimingToBePng() {
        var file = new MockMultipartFile("file", "fake.png", "image/png",
                new byte[] {0x4D, 0x5A, 0x00, 0x00});
        assertThrows(BadRequestException.class, () -> validator.validateMedia(file));
    }

    @Test
    void acceptsMp4ContainerSignatureForVideo() {
        var file = new MockMultipartFile("file", "clip.mp4", "video/mp4",
                new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        assertEquals(UploadContentValidator.DetectedType.VIDEO, validator.validateMedia(file));
    }

    @Test
    void rejectsMismatchedContentType() {
        var file = new MockMultipartFile("file", "clip.mp4", "image/png",
                new byte[] {0, 0, 0, 0, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
        assertThrows(BadRequestException.class, () -> validator.validateMedia(file));
    }
}
