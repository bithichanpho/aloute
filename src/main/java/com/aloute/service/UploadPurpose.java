package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import java.util.Locale;

/** Single source of truth for upload authorization and Cloudinary folder names. */
public enum UploadPurpose {
    POST("post"),
    CHAT("chat"),
    COMMUNITY("community"),
    AVATAR("avatar"),
    GENERAL("general");

    private final String value;

    UploadPurpose(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public String cloudinaryFolder() {
        return "aloute/" + value;
    }

    public boolean ownsPublicId(String publicId) {
        return publicId != null && (publicId.equals(cloudinaryFolder())
                || publicId.startsWith(cloudinaryFolder() + "/"));
    }

    /** Chat attachments may contain arbitrary files without content moderation. */
    public boolean allows(UploadContentValidator.DetectedType type) {
        if (type == null) return false;
        if (type == UploadContentValidator.DetectedType.FILE) return this == CHAT;
        return switch (this) {
            case COMMUNITY, AVATAR -> type == UploadContentValidator.DetectedType.IMAGE;
            case POST, CHAT, GENERAL -> type == UploadContentValidator.DetectedType.IMAGE
                    || type == UploadContentValidator.DetectedType.VIDEO;
        };
    }

    public static UploadPurpose fromRequest(String rawValue) {
        String normalized = rawValue == null || rawValue.isBlank()
                ? "general" : rawValue.trim().toLowerCase(Locale.ROOT);
        for (UploadPurpose purpose : values()) {
            if (purpose.value.equals(normalized)) return purpose;
        }
        throw new BadRequestException("Mục đích upload không hợp lệ");
    }
}
