package com.aloute.service;

import com.aloute.common.util.GoogleAvatarUrl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Shared image fetch/cache avoids browser tracking blocks and bursts to Google's avatar CDN. */
@Service
public class GoogleAvatarService {
    private static final int MAX_IMAGE_BYTES = 512 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/avif");
    private final Downloader downloader;
    private final AvatarImage fallback;
    private final Cache<String, AvatarImage> cache = Caffeine.newBuilder().maximumSize(256)
            .expireAfter(new Expiry<String, AvatarImage>() {
                public long expireAfterCreate(String key, AvatarImage value, long now) {
                    return (value.fallback() ? Duration.ofSeconds(10) : Duration.ofMinutes(15)).toNanos();
                }
                public long expireAfterUpdate(String key, AvatarImage value, long now, long duration) {
                    return expireAfterCreate(key, value, now);
                }
                public long expireAfterRead(String key, AvatarImage value, long now, long duration) { return duration; }
            }).build();

    public GoogleAvatarService() { this(GoogleAvatarService::download, defaultImage()); }

    GoogleAvatarService(Downloader downloader, byte[] fallback) {
        this.downloader = downloader;
        this.fallback = new AvatarImage(fallback, MediaType.IMAGE_PNG, true);
    }

    public AvatarImage get(String url) {
        if (!GoogleAvatarUrl.isSupported(url)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported profile image URL");
        }
        return cache.get(url, key -> {
            try { return downloader.download(URI.create(key)); }
            catch (IOException unavailable) { return fallback; }
        });
    }

    private static AvatarImage download(URI initial) throws IOException {
        URI uri = initial;
        for (int redirect = 0; redirect < 3; redirect++) {
            if (!GoogleAvatarUrl.isSupported(uri.toString())) throw new IOException("Unsupported image origin");
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Aloute-avatar)");
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Missing image redirect");
                    try { uri = uri.resolve(location); }
                    catch (IllegalArgumentException invalid) { throw new IOException("Invalid image redirect"); }
                    continue;
                }
                if (status != 200) throw new IOException("Image provider unavailable");
                String type = connection.getContentType();
                type = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!IMAGE_TYPES.contains(type) || connection.getContentLengthLong() > MAX_IMAGE_BYTES) {
                    throw new IOException("Unsupported image response");
                }
                try (var input = connection.getInputStream()) {
                    byte[] data = input.readNBytes(MAX_IMAGE_BYTES + 1);
                    if (data.length == 0 || data.length > MAX_IMAGE_BYTES) throw new IOException("Invalid image size");
                    return new AvatarImage(data, MediaType.parseMediaType(type), false);
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many image redirects");
    }

    private static byte[] defaultImage() {
        try (var stream = GoogleAvatarService.class.getResourceAsStream("/static/images/avatar_default.png")) {
            if (stream == null) throw new IOException("Missing default avatar");
            return stream.readAllBytes();
        } catch (IOException failure) { throw new IllegalStateException("Cannot load default avatar", failure); }
    }

    public record AvatarImage(byte[] data, MediaType type, boolean fallback) {}
    interface Downloader { AvatarImage download(URI uri) throws IOException; }
}
