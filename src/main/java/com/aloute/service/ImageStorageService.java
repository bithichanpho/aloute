package com.aloute.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

@Service
public class ImageStorageService {

    private final Cloudinary cloudinary;
    private final String configuredCloudName;

    public ImageStorageService(
            Cloudinary cloudinary,
            @Value("${cloudinary.cloud-name:}") String configuredCloudName) {
        this.cloudinary = cloudinary;
        this.configuredCloudName = configuredCloudName == null ? "" : configuredCloudName.trim();
    }

    public Map<String, String> uploadImageDetails(MultipartFile file, UploadPurpose purpose) throws IOException {
        if (purpose == null || !purpose.allows(UploadContentValidator.DetectedType.IMAGE)) {
            throw new IOException("Upload purpose does not allow image uploads");
        }
        String folder = purpose.cloudinaryFolder();
        Map<?, ?> uploadResult =
                cloudinary
                        .uploader()
                        .upload(
                                file.getBytes(),
                                ObjectUtils.asMap(
                                        "folder", folder,
                                        "resource_type", "image"));

        Object secureUrl = uploadResult.get("secure_url");
        if (secureUrl == null) {
            throw new IOException("Cloudinary không trả về secure_url");
        }

        return uploadDetails(uploadResult, file, "image");
    }

    private static final long CHUNKED_UPLOAD_THRESHOLD = 100L * 1024L * 1024L;
    private static final int CHUNK_SIZE = 20 * 1024 * 1024;

    public Map<String, String> uploadMedia(
            MultipartFile file, UploadContentValidator.DetectedType detectedType, UploadPurpose purpose) throws IOException {
        // Defense in depth: never upload a raw/document asset until a dedicated scanner exists,
        // even if a future controller accidentally forgets the purpose/type check.
        if (purpose == null || detectedType == null || !purpose.allows(detectedType)) {
            throw new IOException("Upload purpose/type combination is not supported");
        }
        String resourceType = switch (detectedType) {
            case IMAGE -> "image";
            case VIDEO -> "video";
            case FILE -> throw new IOException("Raw/file uploads are disabled until a dedicated scanner is configured");
        };

        String folder = purpose.cloudinaryFolder();
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

        return uploadDetails(uploadResult, file, resourceType);
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
                type);
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
                                        : resourceType,
                                // Remove already cached public CDN copies of moderated content.
                                "invalidate", true));
    }

    /** Parsed identity for a Cloudinary asset URL stored in the application's managed folder. */
    public record ManagedMediaReference(String publicId, String resourceType) {}

    /**
     * Accept only HTTPS delivery URLs from this application's exact Cloudinary cloud and
     * the aloute/ folder. Host-only validation is insufficient because res.cloudinary.com
     * is shared by every Cloudinary customer.
     */
    public Optional<ManagedMediaReference> resolveAlouteMediaReference(String mediaUrl) {
        if (mediaUrl == null || mediaUrl.isBlank()
                || !configuredCloudName.matches("[A-Za-z0-9_-]{1,128}")) {
            return Optional.empty();
        }
        final URI uri;
        try {
            uri = URI.create(mediaUrl.trim());
        } catch (IllegalArgumentException invalidUrl) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !"res.cloudinary.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawFragment() != null
                || uri.getRawQuery() != null) {
            return Optional.empty();
        }

        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isBlank()) return Optional.empty();
        String lowerRawPath = rawPath.toLowerCase(java.util.Locale.ROOT);
        // Encoded path separators can change the public ID/folder after another URL-decoding pass.
        if (rawPath.indexOf('\\') >= 0
                || lowerRawPath.contains("%2f")
                || lowerRawPath.contains("%5c")
                || lowerRawPath.contains("%252f")
                || lowerRawPath.contains("%255c")) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) return Optional.empty();
        String[] rawSegments = rawPath.split("/");
        String[] segments = path.split("/");
        // Compare the literal first URL path segment so percent-encoded cloud names are rejected.
        if (segments.length < 5 || rawSegments.length < 5
                || !configuredCloudName.equals(rawSegments[1])
                || !configuredCloudName.equals(segments[1])) {
            return Optional.empty();
        }
        for (String segment : segments) {
            if (".".equals(segment) || "..".equals(segment)) return Optional.empty();
        }

        for (String resourceType : java.util.List.of("image", "video", "raw")) {
            String marker = "/" + resourceType + "/upload/";
            int markerIndex = path.indexOf(marker);
            if (markerIndex < 0) continue;
            // Ensure the expected cloud name is the very first segment before the marker.
            if (!path.substring(0, markerIndex).equals("/" + configuredCloudName)) {
                return Optional.empty();
            }
            String deliveryPath = path.substring(markerIndex + marker.length());
            String[] deliverySegments = deliveryPath.split("/");
            int folderIndex = -1;
            for (int i = 0; i < deliverySegments.length; i++) {
                if ("aloute".equals(deliverySegments[i])) {
                    folderIndex = i;
                    break;
                }
            }
            if (folderIndex < 0) return Optional.empty();
            // Any folder/prefix before aloute/ can be a transformation/version; only the canonical
            // application folder after it is used to derive the public ID.
            StringBuilder publicIdBuilder = new StringBuilder("aloute");
            for (int i = folderIndex + 1; i < deliverySegments.length; i++) {
                if (deliverySegments[i].isBlank()) return Optional.empty();
                publicIdBuilder.append('/').append(deliverySegments[i]);
            }
            String publicId = publicIdBuilder.toString();
            // Cloudinary image/video public IDs omit the delivery extension; raw public IDs may
            // include it, so retain it for accurate deletion of legacy raw assets.
            if (!"raw".equals(resourceType)) {
                int lastSlash = publicId.lastIndexOf('/');
                int lastDot = publicId.lastIndexOf('.');
                if (lastDot > lastSlash) publicId = publicId.substring(0, lastDot);
            }
            if (publicId.startsWith("aloute/") && publicId.length() > "aloute/".length()) {
                return Optional.of(new ManagedMediaReference(publicId, resourceType));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

}
