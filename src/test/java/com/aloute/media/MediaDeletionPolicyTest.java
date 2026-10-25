package com.aloute.media;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.aloute.media.domain.MediaDeletionPolicy;
import com.aloute.service.ImageStorageService;
import com.cloudinary.Cloudinary;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MediaDeletionPolicyTest {
    @Test
    void springDefaultsAndCompatibilityConstructorBothDisableDeletion() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        try (var context = new AnnotationConfigApplicationContext(MediaDeletionPolicy.class)) {
            var policy = context.getBean(MediaDeletionPolicy.class);
            assertFalse(policy.permitsPhysicalDeletion());
            new ImageStorageService(cloudinary, "test-cloud", policy).deleteMedia("aloute/post/p", "image");
        }
        new ImageStorageService(cloudinary, "test-cloud").deleteMedia("aloute/post/p", "image");
        verifyNoInteractions(cloudinary);
    }

    @Test
    void bothOptInAndDryRunOffAreRequired() {
        assertFalse(new MediaDeletionPolicy(false, true).permitsPhysicalDeletion());
        assertFalse(new MediaDeletionPolicy(false, false).permitsPhysicalDeletion());
        assertFalse(new MediaDeletionPolicy(true, true).permitsPhysicalDeletion());
        assertTrue(new MediaDeletionPolicy(true, false).permitsPhysicalDeletion());
    }

    @Test
    void dryRunStillGuardsLowLevelStorageEvenWhenPhysicalDeletionIsEnabled() throws Exception {
        Cloudinary cloudinary = mock(Cloudinary.class);
        new ImageStorageService(cloudinary, "test-cloud", new MediaDeletionPolicy(true, true))
                .deleteMedia("aloute/post/p", "image");
        verifyNoInteractions(cloudinary);
    }
}
