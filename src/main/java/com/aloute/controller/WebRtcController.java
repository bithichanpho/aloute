package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.service.WebRtcIceServerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Exposes time-limited ICE credentials only to authenticated users. */
@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/webrtc")
@Tag(name = "WebRTC", description = "Cấu hình STUN/TURN cho cuộc gọi thoại và video")
public class WebRtcController {
    private final WebRtcIceServerService iceServers;

    public WebRtcController(WebRtcIceServerService iceServers) {
        this.iceServers = iceServers;
    }

    @GetMapping("/ice-servers")
    @Operation(summary = "Lấy cấu hình STUN/TURN", description = "TURN credentials có thời hạn và chỉ cấp cho người dùng đã đăng nhập.")
    public ApiResponse<List<Map<String, Object>>> getIceServers(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new UnauthorizedException("Vui lòng đăng nhập để lấy cấu hình cuộc gọi");
        }
        return ApiResponse.success(iceServers.iceServersForUser(authentication.getName()));
    }
}
