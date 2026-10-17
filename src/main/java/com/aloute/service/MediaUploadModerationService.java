package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/** Shared policy for purpose checks, fail-closed moderation, and managed asset cleanup. */
@Service
public class MediaUploadModerationService {
    private static final Logger log = LoggerFactory.getLogger(MediaUploadModerationService.class);

    private final ModerationService moderation;
    private final ImageStorageService storage;
    private final MediaAssetLifecycleService mediaAssets;

    public MediaUploadModerationService(
            ModerationService moderation,
            ImageStorageService storage,
            MediaAssetLifecycleService mediaAssets) {
        this.moderation = moderation;
        this.storage = storage;
        this.mediaAssets = mediaAssets;
    }

    /** A URL must be tracked, belong to the configured Cloudinary cloud, purpose and uploader. */
    public void requirePurposeUrl(
            String url, UploadPurpose purpose, String uploaderId, String... allowedResourceTypes) {
        if (url == null || url.isBlank()) return;
        mediaAssets.requirePurposeUrl(url, purpose, uploaderId, allowedResourceTypes);
    }

    /** Fail closed when moderation is disabled, unavailable, malformed or rejects the media. */
    public void requireApproved(Map<String, String> uploaded, String mediaType, String purposeLabel) {
        if (uploaded == null || uploaded.get("url") == null) {
            throw new IllegalArgumentException("Uploaded asset must include a URL");
        }
        if (!moderation.isEnabled()) {
            deleteQuietly(uploaded);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Kiểm duyệt media đang tắt; không thể phát hành ảnh/video ngoài bài đăng.");
        }
        final boolean approved;
        try {
            approved = moderation.isMediaApprovedForUpload(uploaded.get("url"), mediaType);
        } catch (RuntimeException moderationFailure) {
            deleteQuietly(uploaded);
            throw moderationFailure;
        }
        if (!approved) {
            deleteQuietly(uploaded);
            throw new BadRequestException("Media không vượt qua kiểm duyệt an toàn: " + purposeLabel);
        }
    }

    /** Request lifecycle-managed deletion first; fall back to Cloudinary if the ledger is unavailable. */
    public void deleteQuietly(Map<String, String> uploaded) {
        if (uploaded == null) return;
        String publicId = uploaded.get("publicId");
        String resourceType = uploaded.get("resourceType");
        String url = uploaded.get("url");
        if (publicId == null || publicId.isBlank()) return;
        try {
            if (url != null && !url.isBlank()) {
                mediaAssets.deleteIfUnreferenced(url);
            } else {
                storage.deleteMedia(publicId, resourceType);
            }
        } catch (IOException | RuntimeException cleanupFailure) {
            log.warn("Managed cleanup failed for rejected upload; trying direct delete (cause={})",
                    cleanupFailure.getClass().getSimpleName());
            try {
                storage.deleteMedia(publicId, resourceType);
            } catch (IOException | RuntimeException directFailure) {
                log.warn("Direct cleanup retry failed for rejected upload (cause={})",
                        directFailure.getClass().getSimpleName());
            }
        }
    }
}
