package com.aloute.model.document;

import com.aloute.persistence.postgres.document.PgDocument;

import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;

/**
 * Lifecycle ledger for all Aloute-owned Cloudinary assets.
 *
 * The legacy collection name is deliberately retained so existing post-media records remain
 * visible after this model is generalized to cover chat, community and avatar uploads as well.
 */
@PgDocument(collection = "post_media_assets")
public class ManagedMediaAsset {
    public static final String UPLOADED = "UPLOADED";
    public static final String ATTACHED = "ATTACHED";
    public static final String DELETE_PENDING = "DELETE_PENDING";
    public static final String DELETED = "DELETED";

    @Id
    private String id;

    private String url;
    private String publicId;
    private String resourceType;
    private String purpose;
    private String uploaderId;
    private String attachedToType;
    private String attachedToId;
    private String postId;
    private String status = UPLOADED;
    private LocalDateTime uploadedAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();
    private LocalDateTime deletedAt;
    private int deleteAttempts;
    private String lastDeleteError;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getPublicId() { return publicId; }
    public void setPublicId(String publicId) { this.publicId = publicId; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getUploaderId() { return uploaderId; }
    public void setUploaderId(String uploaderId) { this.uploaderId = uploaderId; }
    public String getAttachedToType() { return attachedToType; }
    public void setAttachedToType(String attachedToType) { this.attachedToType = attachedToType; }
    public String getAttachedToId() { return attachedToId; }
    public void setAttachedToId(String attachedToId) { this.attachedToId = attachedToId; }
    public String getPostId() { return postId; }
    public void setPostId(String postId) { this.postId = postId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public int getDeleteAttempts() { return deleteAttempts; }
    public void setDeleteAttempts(int deleteAttempts) { this.deleteAttempts = Math.max(0, deleteAttempts); }
    public String getLastDeleteError() { return lastDeleteError; }
    public void setLastDeleteError(String lastDeleteError) { this.lastDeleteError = lastDeleteError; }
}
