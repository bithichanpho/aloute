package com.aloute.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class GoogleAvatarServiceTest {
    private static final String GOOGLE = "https://lh3.googleusercontent.com/a/example=s96-c";

    @Test void concurrentProfileAndPostRequestsShareOneDownloadedImage() throws Exception {
        AtomicInteger downloads = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        var expected = new GoogleAvatarService.AvatarImage(new byte[]{1, 2, 3}, MediaType.IMAGE_PNG, false);
        var service = new GoogleAvatarService(uri -> {
            downloads.incrementAndGet();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            return expected;
        }, new byte[]{0});
        try (var pool = Executors.newFixedThreadPool(4)) {
            var requests = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> service.get(GOOGLE))).toList();
            release.countDown();
            for (var request : requests) assertSame(expected, request.get(5, TimeUnit.SECONDS));
        }
        assertSame(expected, service.get(GOOGLE));
        assertEquals(1, downloads.get());
    }

    @Test void unavailableProviderReturnsDefaultWithoutRepeatedRequestsAndRejectsInvalidUrls() {
        AtomicInteger downloads = new AtomicInteger();
        byte[] fallback = {7, 8};
        var service = new GoogleAvatarService(uri -> { downloads.incrementAndGet(); throw new IOException("Unavailable"); }, fallback);
        assertArrayEquals(fallback, service.get(GOOGLE).data());
        assertTrue(service.get(GOOGLE).fallback());
        assertEquals(1, downloads.get());
        assertThrows(ResponseStatusException.class, () -> service.get("http://localhost/admin"));
        assertEquals(1, downloads.get());
    }
}
