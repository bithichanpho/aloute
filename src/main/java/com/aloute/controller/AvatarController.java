package com.aloute.controller;

import com.aloute.service.GoogleAvatarService;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AvatarController {
    private final GoogleAvatarService avatars;

    public AvatarController(GoogleAvatarService avatars) { this.avatars = avatars; }

    @GetMapping("/images/avatars/google")
    public ResponseEntity<byte[]> google(@RequestParam("url") String url) {
        var image = avatars.get(url);
        return ResponseEntity.ok().contentType(image.type())
                .cacheControl(image.fallback() ? CacheControl.noStore() : CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic())
                .header("X-Content-Type-Options", "nosniff").body(image.data());
    }
}
