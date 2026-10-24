package com.aloute.media.api;

/** Media validates uploader ownership and owns the attachment ledger. */
public interface PostMediaAttachments {
    void validatePostMediaUrl(String url, String existingUrl, String expectedUploaderId);
    void attachPostMedia(String url, String postId, String uploaderId);
}
