package com.aloute.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.aloute.common.exception.BadRequestException;
import com.aloute.model.document.ManagedMediaAsset;
import com.aloute.model.document.Post;
import com.aloute.model.document.PostRevision;
import com.aloute.repository.ICommunityRepository;
import com.aloute.repository.IUserRepository;
import com.mongodb.client.result.UpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.Map;
import java.util.Optional;

class MediaAssetLifecycleServiceTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ImageStorageService storage = mock(ImageStorageService.class);
    private final IUserRepository users = mock(IUserRepository.class);
    private final ICommunityRepository communities = mock(ICommunityRepository.class);
    private final MediaAssetLifecycleService service =
            new MediaAssetLifecycleService(mongo, storage, users, communities, 100, 2, 30);

    @BeforeEach
    void clearRepositoryDefaults() {
        when(users.existsByAvatarUrl(anyString())).thenReturn(false);
        when(users.existsByCoverUrl(anyString())).thenReturn(false);
        when(communities.existsByAvatarUrl(anyString())).thenReturn(false);
        when(communities.existsByCoverUrl(anyString())).thenReturn(false);
    }

    @Test
    void registersPostUploadWithPurposeAndUploaderOwnership() {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-image.png";
        Map<String, String> image = Map.of(
                "url", url,
                "publicId", "aloute/post/post-image", "resourceType", "image", "type", "image");
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/post-image", "image")));
        when(mongo.findById("image:aloute/post/post-image", ManagedMediaAsset.class)).thenReturn(null);

        service.registerUpload(image, UploadPurpose.POST, 7L);

        verify(mongo).save(argThat((ManagedMediaAsset asset) ->
                "image:aloute/post/post-image".equals(asset.getId())
                        && ManagedMediaAsset.UPLOADED.equals(asset.getStatus())
                        && "7".equals(asset.getUploaderId())
                        && UploadPurpose.POST.value().equals(asset.getPurpose())
                        && url.equals(asset.getUrl())));
    }

    @Test
    void rejectsPostUploadThatBelongsToAnotherPurpose() {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/chat/chat-image.png";
        Map<String, String> image = Map.of(
                "url", url,
                "publicId", "aloute/chat/chat-image", "resourceType", "image", "type", "image");
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/chat/chat-image", "image")));

        assertThrows(BadRequestException.class, () -> service.registerUpload(image, UploadPurpose.POST, 7L));
        verify(mongo, never()).save(any(ManagedMediaAsset.class));
    }

    @Test
    void hiddenPostMediaIsMarkedForDeletionAndDeletedImmediately() throws Exception {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-image.png";
        ManagedMediaAsset asset = attachedAsset("image:aloute/post/post-image", url,
                "aloute/post/post-image", "image");
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/post-image", "image")));
        when(mongo.findOne(any(Query.class), eq(ManagedMediaAsset.class))).thenReturn(asset);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(ManagedMediaAsset.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        Post post = new Post();
        post.setId("hidden-post");
        post.setImageUrl(url);
        service.deletePostMedia(post);

        verify(storage).deleteMedia("aloute/post/post-image", "image");
        verify(mongo, atLeast(2)).updateFirst(any(Query.class), any(Update.class), eq(ManagedMediaAsset.class));
    }

    @Test
    void hiddenPostDoesNotDeleteMediaStillReferencedByAnotherVisiblePost() throws Exception {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/shared.png";
        ManagedMediaAsset asset = attachedAsset("image:aloute/post/shared", url, "aloute/post/shared", "image");
        Post hiddenPost = new Post();
        hiddenPost.setId("hidden-post");
        hiddenPost.setImageUrl(url);
        Post otherVisiblePost = new Post();
        otherVisiblePost.setId("visible-post");
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/shared", "image")));
        when(mongo.findOne(any(Query.class), eq(Post.class))).thenReturn(otherVisiblePost);
        when(mongo.findOne(any(Query.class), eq(ManagedMediaAsset.class))).thenReturn(asset);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(ManagedMediaAsset.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        service.deletePostMedia(hiddenPost);

        verify(storage, never()).deleteMedia(anyString(), anyString());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(ManagedMediaAsset.class));
    }

    @Test
    void deletesMediaReferencedByPostEditHistoryThroughLifecycle() throws Exception {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-old.png";
        PostRevision revision = new PostRevision();
        revision.setImageUrl(url);
        Post post = new Post();
        post.setId("deleted-post");
        post.setEditHistory(java.util.List.of(revision));
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/post-old", "image")));
        when(mongo.findOne(any(Query.class), eq(ManagedMediaAsset.class))).thenReturn(null);
        when(mongo.findById("image:aloute/post/post-old", ManagedMediaAsset.class)).thenReturn(null);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(ManagedMediaAsset.class)))
                .thenReturn(UpdateResult.acknowledged(1L, 1L, null));

        service.deletePostMedia(post);

        verify(mongo).save(argThat((ManagedMediaAsset asset) ->
                "image:aloute/post/post-old".equals(asset.getId())
                        && ManagedMediaAsset.DELETE_PENDING.equals(asset.getStatus())));
        verify(storage).deleteMedia("aloute/post/post-old", "image");
    }

    @Test
    void rejectsTransformedUrlsWhenTheTrackedOriginalUrlDiffers() {
        String original = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-image.png";
        String transformed = "https://res.cloudinary.com/demo/image/upload/c_fill,w_120/v1/aloute/post/post-image.png";
        ManagedMediaAsset asset = attachedAsset("image:aloute/post/post-image", original,
                "aloute/post/post-image", "image");
        when(storage.resolveAlouteMediaReference(transformed)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/post-image", "image")));
        when(mongo.findOne(any(Query.class), eq(ManagedMediaAsset.class))).thenReturn(null);
        when(mongo.findById("image:aloute/post/post-image", ManagedMediaAsset.class)).thenReturn(asset);

        assertThrows(BadRequestException.class,
                () -> service.validatePostMediaUrl(transformed, null, "7"));
    }

    @Test
    void rejectsMediaUploadedForAnotherPurposeWhenCreatingPost() {
        String chatUrl = "https://res.cloudinary.com/demo/image/upload/v1/aloute/chat/photo.png";
        when(storage.resolveAlouteMediaReference(chatUrl)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/chat/photo", "image")));

        assertThrows(BadRequestException.class, () -> service.validatePostMediaUrl(chatUrl, null, "7"));
    }

    @Test
    void unchangedLegacyExternalUrlMayRemainOnAnEditButCannotBeIntroducedAsNewMedia() {
        String legacyUrl = "https://legacy.example/old-photo.png";

        assertDoesNotThrow(() -> service.validatePostMediaUrl(legacyUrl, legacyUrl, "7"));
        assertThrows(BadRequestException.class, () -> service.validatePostMediaUrl(legacyUrl, null, "7"));
    }

    @Test
    void rejectsApprovalAfterMediaHasBeenDeleted() {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-image.png";
        ManagedMediaAsset deleted = attachedAsset("image:aloute/post/post-image", url,
                "aloute/post/post-image", "image");
        deleted.setStatus(ManagedMediaAsset.DELETED);
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/post-image", "image")));
        when(mongo.findOne(any(Query.class), eq(ManagedMediaAsset.class))).thenReturn(deleted);
        Post post = new Post();
        post.setImageUrl(url);

        assertThrows(BadRequestException.class, () -> service.assertCanApprove(post));
    }

    @Test
    void refusesToAttachTrackedMediaWhenUploaderDoesNotOwnIt() {
        String url = "https://res.cloudinary.com/demo/image/upload/v1/aloute/post/post-image.png";
        ManagedMediaAsset asset = attachedAsset("image:aloute/post/post-image", url,
                "aloute/post/post-image", "image");
        asset.setUploaderId("8");
        when(storage.resolveAlouteMediaReference(url)).thenReturn(
                Optional.of(new ImageStorageService.ManagedMediaReference("aloute/post/post-image", "image")));
        when(mongo.findOne(any(Query.class), eq(ManagedMediaAsset.class))).thenReturn(asset);

        assertThrows(BadRequestException.class,
                () -> service.requirePurposeUrl(url, UploadPurpose.POST, "7", "image", "video"));
    }

    @Test
    void refusesToDeleteExternalOrAnotherCloudsAssets() throws Exception {
        Post post = new Post();
        post.setImageUrl("https://example.invalid/private.png");
        post.setVideoUrl("https://res.cloudinary.com/other-cloud/video/upload/v1/aloute/post/video.mp4");
        when(storage.resolveAlouteMediaReference(anyString())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.deletePostMedia(post));
        verify(storage, never()).deleteMedia(anyString(), anyString());
        verify(mongo, never()).save(any(ManagedMediaAsset.class));
    }

    private ManagedMediaAsset attachedAsset(String id, String url, String publicId, String resourceType) {
        ManagedMediaAsset asset = new ManagedMediaAsset();
        asset.setId(id);
        asset.setUrl(url);
        asset.setPublicId(publicId);
        asset.setResourceType(resourceType);
        asset.setPurpose(UploadPurpose.POST.value());
        asset.setUploaderId("7");
        asset.setStatus(ManagedMediaAsset.ATTACHED);
        return asset;
    }
}
