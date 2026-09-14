package com.aloute.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

@Service
public class ImageStorageService {

    private final Cloudinary cloudinary;

    public ImageStorageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Upload image used for avatar/post images.
     */
    public String uploadImage(MultipartFile file) throws IOException {
        Map<?, ?> uploadResult = cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "folder", "aloute",
                        "resource_type", "image"
                )
        );

        Object secureUrl = uploadResult.get("secure_url");
        if (secureUrl == null) {
            throw new IOException("Cloudinary không trả về secure_url");
        }

        return secureUrl.toString();
    }

    /**
     * Upload image/video used by the chat.
     * resource_type=auto lets Cloudinary detect image or video automatically.
     */
    private static final long CHUNKED_UPLOAD_THRESHOLD = 100L * 1024L * 1024L; // 100MB
    private static final int CHUNK_SIZE = 20 * 1024 * 1024; // 20MB/chunk

    public Map<String, String> uploadMedia(MultipartFile file) throws IOException {
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String resourceType = contentType.startsWith("image/")
                ? "image"
                : contentType.startsWith("video/") ? "video" : "raw";

        String folder = "raw".equals(resourceType) ? "aloute/chat/files" : "aloute/chat";
        Map<String, Object> options = ObjectUtils.asMap(
                "folder", folder,
                "resource_type", resourceType,
                "use_filename", true,
                "unique_filename", true
        );

        Map<?, ?> uploadResult;
        Path tempPath = null;
        try {
            if (file.getSize() > CHUNKED_UPLOAD_THRESHOLD) {
                // Cloudinary requires chunked upload for files > 100MB.
                // Write the MultipartFile to disk first so a multi-GB upload does not
                // require loading the whole file into JVM heap.
                tempPath = Files.createTempFile("aloute-upload-", ".tmp");
                file.transferTo(tempPath.toFile());
                uploadResult = cloudinary.uploader().uploadLarge(
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

        String url = secureUrlValue.toString();
        String type = "raw".equals(resourceType) ? "file" : resourceType;
        String originalName = file.getOriginalFilename() == null ? "tep-dinh-kem" : file.getOriginalFilename();

        return Map.of(
                "url", url,
                "type", type,
                "name", originalName,
                "size", String.valueOf(file.getSize())
        );
    }

    /**
     * Delete a Cloudinary resource by public_id.
     */
    public void deleteImage(String publicId) throws IOException {
        if (publicId == null || publicId.isBlank()) {
            return;
        }

        cloudinary.uploader().destroy(
                publicId,
                ObjectUtils.emptyMap()
        );
    }
}
