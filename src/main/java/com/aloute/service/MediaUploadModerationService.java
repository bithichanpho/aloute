package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Shared policy for purpose checks, fail-closed moderation, and managed asset cleanup. */
@Service
public class MediaUploadModerationService {
    private static final Logger log = LoggerFactory.getLogger(MediaUploadModerationService.class);

    private final ModerationService moderation;
    private final MediaAssetLifecycleService mediaAssets;

    public MediaUploadModerationService(
            ModerationService moderation,
            ImageStorageService storage,
            MediaAssetLifecycleService mediaAssets) {
        this.moderation = moderation;
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

    /** Delegate all rejected-upload cleanup to the single lifecycle owner. */
    public void deleteQuietly(Map<String, String> uploaded) {
        if (uploaded == null) return;
        try {
            mediaAssets.deleteRejectedUpload(uploaded);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Rejected upload cleanup could not be queued (cause={})",
                    cleanupFailure.getClass().getSimpleName());
        }
    }
}
