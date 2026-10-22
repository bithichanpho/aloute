package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.document.ChatMessage;
import com.aloute.model.document.ManagedMediaAsset;
import com.aloute.model.document.ModerationStatus;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IUserRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import com.aloute.persistence.postgres.document.PostgresDocumentStore;
import com.aloute.persistence.postgres.document.Criteria;
import com.aloute.persistence.postgres.document.Query;
import com.aloute.persistence.postgres.document.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single lifecycle owner for all Cloudinary assets managed by ALOUTE.
 *
 * The legacy PostgreSQL document collection name remains `post_media_assets` to preserve the post-media ledger
 * already written by previous builds. New records include purpose/uploader/reference metadata.
 */
@Service
public class MediaAssetLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(MediaAssetLifecycleService.class);

    private final PostgresDocumentStore documentStore;
    private final ImageStorageService storage;
    private final IUserRepository users;
    private final ICommunityRepository communities;
    private final int scanLimit;
    private final int orphanAgeHours;
    private final int deletedRetentionDays;

    public MediaAssetLifecycleService(
            PostgresDocumentStore documentStore,
            ImageStorageService storage,
            IUserRepository users,
            ICommunityRepository communities,
            @Value("${app.media.cleanup.scan-limit:100}") int scanLimit,
            @Value("${app.media.cleanup.orphan-age-hours:2}") int orphanAgeHours,
            @Value("${app.media.cleanup.deleted-retention-days:30}") int deletedRetentionDays) {
        this.documentStore = documentStore;
        this.storage = storage;
        this.users = users;
        this.communities = communities;
        this.scanLimit = Math.max(10, Math.min(500, scanLimit));
        this.orphanAgeHours = Math.max(1, Math.min(168, orphanAgeHours));
        this.deletedRetentionDays = Math.max(1, Math.min(365, deletedRetentionDays));
    }

    /** Register every image/video upload, including media that is not attached to a post. */
    public void registerUpload(Map<String, String> uploaded, UploadPurpose purpose, Long uploaderId) {
        if (uploaded == null || purpose == null || uploaderId == null) {
            throw new IllegalArgumentException("Uploaded media, purpose and uploader are required");
        }
        String url = trimToNull(uploaded.get("url"));
        String publicId = trimToNull(uploaded.get("publicId"));
        String resourceType = trimToNull(uploaded.get("resourceType"));
        String type = trimToNull(uploaded.get("type"));
        if (url == null || publicId == null || resourceType == null
                || !("image".equals(type) || "video".equals(type))
                || !type.equals(resourceType)) {
            throw new BadRequestException("Thông tin media tải lên không hợp lệ");
        }
        ImageStorageService.ManagedMediaReference reference = storage.resolveAlouteMediaReference(url)
                .orElseThrow(() -> new BadRequestException("Cloudinary trả về URL không thuộc cloud ALOUTE"));
        if (!purpose.ownsPublicId(publicId) || !publicId.equals(reference.publicId())
                || !resourceType.equals(reference.resourceType())) {
            throw new BadRequestException("Media không khớp purpose hoặc Cloudinary public ID đã cấp");
        }

        String id = resourceType + ":" + publicId;
        ManagedMediaAsset existing = documentStore.findById(id, ManagedMediaAsset.class);
        if (existing != null) {
            if (ManagedMediaAsset.DELETED.equals(existing.getStatus())) {
                throw new BadRequestException("Cloudinary public ID này đã bị thu hồi; vui lòng tải tệp khác");
            }
            if (url.equals(existing.getUrl())
                    && purpose.value().equals(existing.getPurpose())
                    && String.valueOf(uploaderId).equals(existing.getUploaderId())) {
                return; // idempotent retry from the same authenticated upload request
            }
            throw new BadRequestException("Media đã được đăng ký bởi một lượt upload khác");
        }

        ManagedMediaAsset asset = new ManagedMediaAsset();
        asset.setId(id);
        asset.setUrl(url);
        asset.setPublicId(publicId);
        asset.setResourceType(resourceType);
        asset.setPurpose(purpose.value());
        asset.setUploaderId(String.valueOf(uploaderId));
        asset.setStatus(ManagedMediaAsset.UPLOADED);
        LocalDateTime now = LocalDateTime.now();
        asset.setUploadedAt(now);
        asset.setUpdatedAt(now);
        documentStore.save(asset);
    }

    /** Require a tracked, original URL uploaded by this user for this exact purpose. */
    public void requirePurposeUrl(String url, UploadPurpose purpose, String uploaderId,
                                   String... allowedResourceTypes) {
        String normalized = trimToNull(url);
        if (normalized == null) return;
        if (purpose == null) throw new IllegalArgumentException("Upload purpose is required");
        if (uploaderId == null || uploaderId.isBlank()) {
            throw new BadRequestException("Không thể xác minh người tải media lên");
        }
        ImageStorageService.ManagedMediaReference reference = storage.resolveAlouteMediaReference(normalized)
                .orElseThrow(() -> new BadRequestException(
                        "Media phải dùng HTTPS, đúng Cloudinary cloud ALOUTE và thư mục upload quản lý"));
        boolean typeAllowed = allowedResourceTypes == null || allowedResourceTypes.length == 0;
        if (allowedResourceTypes != null) {
            for (String type : allowedResourceTypes) {
                if (type != null && type.equals(reference.resourceType())) {
                    typeAllowed = true;
                    break;
                }
            }
        }
        UploadContentValidator.DetectedType detectedType = switch (reference.resourceType()) {
            case "image" -> UploadContentValidator.DetectedType.IMAGE;
            case "video" -> UploadContentValidator.DetectedType.VIDEO;
            default -> UploadContentValidator.DetectedType.FILE;
        };
        if (!purpose.ownsPublicId(reference.publicId()) || !typeAllowed || !purpose.allows(detectedType)) {
            throw new BadRequestException("Media không thuộc mục đích " + purpose.value()
                    + " hoặc loại tệp này chưa được kiểm duyệt");
        }
        ManagedMediaAsset asset = findTrackedAsset(normalized, reference);
        if (asset == null || !normalized.equals(asset.getUrl())
                || !purpose.value().equals(asset.getPurpose())
                || !uploaderId.equals(asset.getUploaderId())) {
            throw new BadRequestException(
                    "Không thể xác minh chủ sở hữu hoặc mục đích của media; hãy upload lại bằng tài khoản hiện tại");
        }
        if (ManagedMediaAsset.DELETE_PENDING.equals(asset.getStatus())
                || ManagedMediaAsset.DELETED.equals(asset.getStatus())) {
            throw new BadRequestException("Media này đã bị thu hồi hoặc xóa");
        }
    }

    /** New post URLs must be tracked post-purpose uploads created by the post author. */
    public void validatePostMediaUrl(String url, String existingUrl, String expectedUploaderId) {
        String normalized = trimToNull(url);
        if (normalized == null) return;
        if (normalized.equals(trimToNull(existingUrl))) return; // preserve an unchanged legacy URL
        requirePurposeUrl(normalized, UploadPurpose.POST, expectedUploaderId, "image", "video");
    }

    public void attachPostMedia(String url, String postId, String uploaderId) {
        attachManagedReference(url, UploadPurpose.POST, uploaderId, "POST", postId, postId);
    }

    public void attachManagedReference(String url, UploadPurpose purpose, String uploaderId,
                                       String attachedToType, String attachedToId) {
        attachManagedReference(url, purpose, uploaderId, attachedToType, attachedToId, null);
    }

    private void attachManagedReference(String url, UploadPurpose purpose, String uploaderId,
                                        String attachedToType, String attachedToId, String postId) {
        String normalized = trimToNull(url);
        if (normalized == null || purpose == null || uploaderId == null) return;
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("url").is(normalized),
                Criteria.where("purpose").is(purpose.value()),
                Criteria.where("uploaderId").is(uploaderId),
                Criteria.where("status").in(ManagedMediaAsset.UPLOADED, ManagedMediaAsset.ATTACHED)));
        Update update = new Update()
                .set("status", ManagedMediaAsset.ATTACHED)
                .set("attachedToType", attachedToType)
                .set("attachedToId", attachedToId)
                .set("updatedAt", LocalDateTime.now());
        if (postId == null) update.unset("postId");
        else update.set("postId", postId);
        documentStore.updateFirst(query, update, ManagedMediaAsset.class);
    }

    /** Block APPROVE after HIDE already caused an asset to be deleted or queued for deletion. */
    public void assertCanApprove(Post post) {
        if (post == null) return;
        for (String url : postMediaUrls(post)) {
            ImageStorageService.ManagedMediaReference reference = storage.resolveAlouteMediaReference(url).orElse(null);
            ManagedMediaAsset asset = findTrackedAsset(url, reference);
            if (asset != null && (ManagedMediaAsset.DELETED.equals(asset.getStatus())
                    || ManagedMediaAsset.DELETE_PENDING.equals(asset.getStatus()))) {
                throw new BadRequestException(
                        "Không thể duyệt lại bài vì media đã bị xóa/đang chờ xóa. Người đăng cần upload lại và tạo bài mới.");
            }
        }
    }

    /** Delete current and historical media after a post is hidden or deleted. */
    public void deletePostMedia(Post post) {
        if (post == null) return;
        for (String url : postMediaUrls(post)) requestDelete(url, post.getId());
    }

    /**
     * Persist deletion tombstones without performing Cloudinary I/O. Call this after removing the
     * post inside the same PostgreSQL transaction; the scheduled lifecycle worker performs the external
     * deletion after commit and retries failures from the durable ledger.
     */
    public void queuePostMediaDeletion(Post post) {
        if (post == null) return;
        for (String url : postMediaUrls(post)) queueDelete(url, post.getId(), true);
    }

    /** Queue deletion without external I/O; intended for use inside the owner's PostgreSQL transaction. */
    public void queueUnreferencedDeletion(String url) {
        queueDelete(url, null, true);
    }

    /**
     * Queue every media URL of a hidden post, then mark the post reconciled. If queuing fails, the
     * marker is not written and the scheduled scanner retries it.
     */
    public void queueHiddenPostMedia(Post post) {
        if (post == null || post.getId() == null) return;
        queuePostMediaDeletion(post);
        documentStore.updateFirst(Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(post.getId()),
                        Criteria.where("moderationStatus").is(ModerationStatus.HIDDEN))),
                new Update().set("mediaCleanupQueuedAt", LocalDateTime.now()), Post.class);
    }

    /** Lifecycle-owned cleanup for an upload rejected before it can be attached. */
    public void deleteRejectedUpload(Map<String, String> uploaded) {
        if (uploaded == null) return;
        String url = trimToNull(uploaded.get("url"));
        String publicId = trimToNull(uploaded.get("publicId"));
        String resourceType = trimToNull(uploaded.get("resourceType"));
        if (url != null) {
            try {
                ManagedMediaAsset queued = queueDelete(url, null, true);
                if (queued != null) attemptDelete(queued);
                return;
            } catch (RuntimeException ledgerFailure) {
                log.warn("Media ledger unavailable for rejected upload; attempting direct cleanup (cause={})",
                        ledgerFailure.getClass().getSimpleName());
            }
        }
        if (publicId == null) return;
        try {
            storage.deleteMedia(publicId, resourceType);
        } catch (IOException | RuntimeException failure) {
            log.warn("Rejected upload cleanup failed (cause={})",
                    failure.getClass().getSimpleName());
        }
    }

    /** Cleanup an old avatar/community/chat URL after its owning record stops referencing it. */
    public void deleteIfUnreferenced(String url) {
        requestDelete(url, null);
    }

    private void requestDelete(String url, String deletingPostId) {
        ManagedMediaAsset asset = queueDelete(url, deletingPostId, false);
        if (asset != null) attemptDelete(asset);
    }

    private ManagedMediaAsset queueDelete(
            String url, String deletingPostId, boolean requireDurableTombstone) {
        String normalized = trimToNull(url);
        if (normalized == null) return null;
        ImageStorageService.ManagedMediaReference reference = storage.resolveAlouteMediaReference(normalized).orElse(null);
        if (reference == null) return null; // never delete external or another Cloudinary account's assets

        if (hasActiveReference(normalized, deletingPostId)) {
            ManagedMediaAsset shared = findTrackedAsset(normalized, reference);
            if (shared != null && !ManagedMediaAsset.DELETED.equals(shared.getStatus())) {
                documentStore.updateFirst(Query.query(Criteria.where("_id").is(shared.getId())),
                        new Update().set("status", ManagedMediaAsset.ATTACHED)
                                .set("updatedAt", LocalDateTime.now()), ManagedMediaAsset.class);
            }
            return null;
        }

        ManagedMediaAsset asset = findTrackedAsset(normalized, reference);
        if (asset == null) {
            // Create a tombstone even for legacy post URLs so an admin cannot later APPROVE a post
            // whose asset was removed. The collection/key scheme is shared across old and new records.
            asset = new ManagedMediaAsset();
            asset.setId(reference.resourceType() + ":" + reference.publicId());
            asset.setUrl(normalized);
            asset.setPublicId(reference.publicId());
            asset.setResourceType(reference.resourceType());
            asset.setPurpose(purposeFor(reference.publicId()));
            asset.setPostId(deletingPostId);
            asset.setStatus(ManagedMediaAsset.DELETE_PENDING);
            asset.setUploadedAt(LocalDateTime.now());
            asset.setUpdatedAt(LocalDateTime.now());
            try {
                documentStore.save(asset);
            } catch (RuntimeException duplicateOrStoreError) {
                ManagedMediaAsset existing = findTrackedAsset(normalized, reference);
                if (existing == null) {
                    if (requireDurableTombstone) throw duplicateOrStoreError;
                    log.warn("Unable to create media deletion tombstone (cause={})",
                            duplicateOrStoreError.getClass().getSimpleName());
                    // Fail safe: we do not delete without a persistent tombstone.
                    return null;
                }
                asset = existing;
            }
        }
        if (ManagedMediaAsset.DELETED.equals(asset.getStatus())) return null;
        documentStore.updateFirst(Query.query(Criteria.where("_id").is(asset.getId())),
                new Update().set("status", ManagedMediaAsset.DELETE_PENDING)
                        .set("updatedAt", LocalDateTime.now()), ManagedMediaAsset.class);
        asset.setStatus(ManagedMediaAsset.DELETE_PENDING);
        return asset;
    }

    @Scheduled(
            fixedDelayString = "${app.media.cleanup.interval-ms:3600000}",
            initialDelayString = "${app.media.cleanup.initial-delay-ms:300000}")
    @SchedulerLock(name = "mediaAssetLifecycleCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void cleanupOrphanedUploadsAndPendingDeletes() {
        LocalDateTime now = LocalDateTime.now();
        reconcileHiddenPostMedia();
        LocalDateTime cutoff = now.minusHours(orphanAgeHours);
        Criteria work = new Criteria().orOperator(
                Criteria.where("status").is(ManagedMediaAsset.DELETE_PENDING),
                new Criteria().andOperator(
                        Criteria.where("status").is(ManagedMediaAsset.UPLOADED),
                        Criteria.where("uploadedAt").lte(cutoff)));
        List<ManagedMediaAsset> candidates = documentStore.find(
                Query.query(work)
                        .with(Sort.by(Sort.Order.asc("updatedAt"), Sort.Order.asc("uploadedAt")))
                        .limit(scanLimit),
                ManagedMediaAsset.class);
        for (ManagedMediaAsset asset : candidates) {
            try {
                if (hasActiveReference(asset.getUrl(), null)) {
                    markDiscoveredReference(asset);
                } else {
                    requestDelete(asset.getUrl(), asset.getPostId());
                }
            } catch (RuntimeException candidateFailure) {
                log.warn("Media lifecycle scan skipped one asset (cause={})",
                        candidateFailure.getClass().getSimpleName());
            }
        }

        // Retain tombstones while any post (including HIDDEN posts) still refers to the URL, so
        // APPROVE remains blocked after deletion. Purge only unreferenced, old tombstones.
        List<ManagedMediaAsset> oldTombstones = documentStore.find(
                Query.query(new Criteria().andOperator(
                        Criteria.where("status").is(ManagedMediaAsset.DELETED),
                        Criteria.where("deletedAt").lt(now.minusDays(deletedRetentionDays))))
                        .with(Sort.by(Sort.Order.asc("deletedAt"))).limit(scanLimit),
                ManagedMediaAsset.class);
        for (ManagedMediaAsset asset : oldTombstones) {
            if (!hasAnyPostReference(asset.getUrl()) && !hasNonPostReference(asset.getUrl())) {
                documentStore.remove(Query.query(Criteria.where("_id").is(asset.getId())), ManagedMediaAsset.class);
            }
        }
    }

    private void reconcileHiddenPostMedia() {
        List<Post> hidden = documentStore.find(
                Query.query(new Criteria().andOperator(
                                Criteria.where("moderationStatus").is(ModerationStatus.HIDDEN),
                                Criteria.where("mediaCleanupQueuedAt").is(null)))
                        .with(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("_id")))
                        .limit(scanLimit),
                Post.class);
        for (Post post : hidden) {
            try {
                queueHiddenPostMedia(post);
            } catch (RuntimeException failure) {
                log.warn("Hidden post media reconciliation deferred (postId={}, cause={})",
                        post.getId(), failure.getClass().getSimpleName());
            }
        }
    }

    private void markDiscoveredReference(ManagedMediaAsset asset) {
        Post post = findReferencedNonHiddenPost(asset.getUrl(), null);
        Update update = new Update().set("status", ManagedMediaAsset.ATTACHED)
                .set("updatedAt", LocalDateTime.now()).unset("lastDeleteError");
        if (post != null) {
            update.set("purpose", UploadPurpose.POST.value())
                    .set("attachedToType", "POST")
                    .set("attachedToId", post.getId())
                    .set("postId", post.getId());
        } else if (documentStore.exists(Query.query(Criteria.where("mediaUrl").is(asset.getUrl())), ChatMessage.class)) {
            update.set("attachedToType", "CHAT_MESSAGE");
        } else {
            update.set("attachedToType", "OTHER");
        }
        documentStore.updateFirst(Query.query(Criteria.where("_id").is(asset.getId())), update, ManagedMediaAsset.class);
    }

    private boolean hasActiveReference(String url, String excludedPostId) {
        if (findReferencedNonHiddenPost(url, excludedPostId) != null) return true;
        return hasNonPostReference(url);
    }

    private boolean hasNonPostReference(String url) {
        if (url == null || url.isBlank()) return false;
        if (documentStore.exists(Query.query(Criteria.where("mediaUrl").is(url)), ChatMessage.class)) return true;
        return users.existsByAvatarUrl(url)
                || users.existsByCoverUrl(url)
                || communities.existsByAvatarUrl(url)
                || communities.existsByCoverUrl(url);
    }

    private boolean hasAnyPostReference(String url) {
        if (url == null || url.isBlank()) return false;
        return documentStore.exists(Query.query(new Criteria().orOperator(
                Criteria.where("imageUrl").is(url), Criteria.where("videoUrl").is(url),
                Criteria.where("editHistory.imageUrl").is(url), Criteria.where("editHistory.videoUrl").is(url))),
                Post.class);
    }

    private Post findReferencedNonHiddenPost(String url, String excludedPostId) {
        if (url == null || url.isBlank()) return null;
        List<Criteria> clauses = new ArrayList<>();
        clauses.add(Criteria.where("moderationStatus").ne(ModerationStatus.HIDDEN));
        if (excludedPostId != null && !excludedPostId.isBlank()) {
            clauses.add(Criteria.where("_id").ne(excludedPostId));
        }
        clauses.add(new Criteria().orOperator(
                Criteria.where("imageUrl").is(url), Criteria.where("videoUrl").is(url),
                Criteria.where("editHistory.imageUrl").is(url), Criteria.where("editHistory.videoUrl").is(url)));
        return documentStore.findOne(Query.query(new Criteria().andOperator(clauses.toArray(Criteria[]::new))), Post.class)
                .orElse(null);
    }

    private ManagedMediaAsset findTrackedAsset(
            String url, ImageStorageService.ManagedMediaReference reference) {
        ManagedMediaAsset asset = documentStore.findOne(
                Query.query(Criteria.where("url").is(url)), ManagedMediaAsset.class).orElse(null);
        if (asset != null || reference == null) return asset;
        return documentStore.findById(reference.resourceType() + ":" + reference.publicId(), ManagedMediaAsset.class);
    }

    private void attemptDelete(ManagedMediaAsset asset) {
        try {
            storage.deleteMedia(asset.getPublicId(), asset.getResourceType());
            documentStore.updateFirst(Query.query(Criteria.where("_id").is(asset.getId())),
                    new Update().set("status", ManagedMediaAsset.DELETED)
                            .set("deletedAt", LocalDateTime.now())
                            .set("updatedAt", LocalDateTime.now())
                            .unset("lastDeleteError"), ManagedMediaAsset.class);
        } catch (IOException | RuntimeException failure) {
            documentStore.updateFirst(Query.query(Criteria.where("_id").is(asset.getId())),
                    new Update().set("status", ManagedMediaAsset.DELETE_PENDING)
                            .set("lastDeleteError", failure.getClass().getSimpleName())
                            .set("updatedAt", LocalDateTime.now())
                            .inc("deleteAttempts", 1), ManagedMediaAsset.class);
            log.warn("Cloudinary media cleanup deferred (assetId={}, cause={})",
                    asset.getId(), failure.getClass().getSimpleName());
        }
    }

    private static String purposeFor(String publicId) {
        if (publicId == null) return UploadPurpose.GENERAL.value();
        for (UploadPurpose purpose : UploadPurpose.values()) {
            if (purpose.ownsPublicId(publicId)) return purpose.value();
        }
        return UploadPurpose.GENERAL.value();
    }

    private static List<String> postMediaUrls(Post post) {
        Set<String> urls = new LinkedHashSet<>();
        addUrl(urls, post.getImageUrl());
        addUrl(urls, post.getVideoUrl());
        if (post.getEditHistory() != null) {
            for (PostRevision revision : post.getEditHistory()) {
                if (revision != null) {
                    addUrl(urls, revision.getImageUrl());
                    addUrl(urls, revision.getVideoUrl());
                }
            }
        }
        return List.copyOf(urls);
    }

    private static void addUrl(Set<String> urls, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) urls.add(normalized);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
