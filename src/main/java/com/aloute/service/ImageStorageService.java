package com.aloute.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Service
public class ImageStorageService {

    private final Cloudinary cloudinary;

    public ImageStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    public String uploadImage(MultipartFile file) throws IOException {
        return uploadImageDetails(file).get("url");
    }

    public Map<String, String> uploadImageDetails(MultipartFile file) throws IOException {
        Map<?, ?> uploadResult =
                cloudinary
                        .uploader()
                        .upload(
                                file.getBytes(),
                                ObjectUtils.asMap(
                                        "folder", "aloute",
                                        "resource_type", "image"));

        Object secureUrl = uploadResult.get("secure_url");
        if (secureUrl == null) {
            throw new IOException("Cloudinary không trả về secure_url");
        }

        return uploadDetails(uploadResult, file, "image");
    }

    private static final long CHUNKED_UPLOAD_THRESHOLD = 100L * 1024L * 1024L;
    private static final int CHUNK_SIZE = 20 * 1024 * 1024;

    public Map<String, String> uploadMedia(MultipartFile file) throws IOException {
        String contentType =
                file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String resourceType =
                contentType.startsWith("image/")
                        ? "image"
                        : contentType.startsWith("video/") ? "video" : "raw";

        String folder = "raw".equals(resourceType) ? "aloute/chat/files" : "aloute/chat";
        Map<String, Object> options =
                new java.util.HashMap<>(
                        Map.of(
                                "folder",
                                folder,
                                "resource_type",
                                resourceType,
                                "use_filename",
                                true,
                                "unique_filename",
                                true));

        Map<?, ?> uploadResult;
        Path tempPath = null;
        try {
            if (file.getSize() > CHUNKED_UPLOAD_THRESHOLD) {

                tempPath = Files.createTempFile("aloute-upload-", ".tmp");
                file.transferTo(tempPath.toFile());
                uploadResult =
                        cloudinary
                                .uploader()
                                .uploadLarge(
                                        tempPath.toAbsolutePath().toString(), options, CHUNK_SIZE);
            } else {
                uploadResult = cloudinary.uploader().upload(file.getBytes(), options);
            }
        } finally {
            if (tempPath != null) {
                Files.deleteIfExists(tempPath);
            }
        }

        Object secureUrlValue = uploadResult.get("secure_url");
        if (secureUrlValue == null) {
            throw new IOException("Cloudinary không trả về secure_url");
        }

        String type = "raw".equals(resourceType) ? "file" : resourceType;
        return uploadDetails(uploadResult, file, type);
    }

    private Map<String, String> uploadDetails(
            Map<?, ?> uploadResult, MultipartFile file, String type) throws IOException {
        Object url = uploadResult.get("secure_url");
        Object publicId = uploadResult.get("public_id");
        if (url == null || publicId == null) {
            throw new IOException("Cloudinary không trả về đủ thông tin tệp");
        }
        String originalName =
                file.getOriginalFilename() == null ? "tep-dinh-kem" : file.getOriginalFilename();
        return Map.of(
                "url",
                url.toString(),
                "type",
                type,
                "name",
                originalName,
                "size",
                String.valueOf(file.getSize()),
                "publicId",
                publicId.toString(),
                "resourceType",
                "file".equals(type) ? "raw" : type);
    }

    public void deleteMedia(String publicId, String resourceType) throws IOException {
        if (publicId == null || publicId.isBlank()) return;
        cloudinary
                .uploader()
                .destroy(
                        publicId,
                        ObjectUtils.asMap(
                                "resource_type",
                                resourceType == null || resourceType.isBlank()
                                        ? "image"
                                        : resourceType));
    }

    public void deleteImage(String publicId) throws IOException {
        if (publicId == null || publicId.isBlank()) {
            return;
        }

        cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
    }

    public void deleteAlouteImageByUrl(String imageUrl) throws IOException {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        String path = URI.create(imageUrl).getPath();
        String marker = "/image/upload/";
        int start = path.indexOf(marker);
        if (start < 0) {
            return;
        }

        String publicId =
                path.substring(start + marker.length())
                        .replaceFirst("^v\\d+/", "")
                        .replaceFirst("\\.[^.]+$", "");

        if (!publicId.startsWith("aloute/")) {
            return;
        }

        deleteImage(publicId);
    }

    public void deleteAlouteMediaByUrl(String mediaUrl) throws IOException {
        if (mediaUrl == null || mediaUrl.isBlank()) return;
        String path = URI.create(mediaUrl).getPath();
        String imageMarker = "/image/upload/";
        String videoMarker = "/video/upload/";
        String marker = path.contains(videoMarker) ? videoMarker : imageMarker;
        int start = path.indexOf(marker);
        if (start < 0) return;
        String publicId =
                path.substring(start + marker.length())
                        .replaceFirst("^v\\d+/", "")
                        .replaceFirst("\\.[^.]+$", "");
        if (!publicId.startsWith("aloute/")) return;
        deleteMedia(publicId, marker.equals(videoMarker) ? "video" : "image");
    }
}
