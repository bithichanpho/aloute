package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.response.ApiResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AccountSecurityService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/account")
public class AccountSecurityController {
    private final AccountSecurityService service;

    public AccountSecurityController(AccountSecurityService service) {
        this.service = service;
    }

    public record Credentials(
            @Size(max = 200) String password,
            @Size(max = 100) String code,
            @Size(max = 5000) String firebaseIdToken) {}

    @GetMapping("/2fa")
    public ApiResponse<?> status(Authentication a) {
        return ApiResponse.success(
                java.util.Map.of("enabled", service.enabled(SecurityUtils.currentUserId(a))));
    }

    @PostMapping("/2fa/setup")
    public ApiResponse<?> setup(Authentication a, @Valid @RequestBody Credentials c) {
        return ApiResponse.success(service.setup(
                SecurityUtils.currentUserId(a), c.password(), c.firebaseIdToken()));
    }

    @PostMapping("/2fa/enable")
    public ApiResponse<?> enable(Authentication a, @Valid @RequestBody Credentials c) {
        return ApiResponse.success(
                service.enable(SecurityUtils.currentUserId(a), c.password(), c.code(), c.firebaseIdToken()));
    }

    @PostMapping("/2fa/disable")
    public ApiResponse<?> disable(Authentication a, @Valid @RequestBody Credentials c) {
        service.disable(SecurityUtils.currentUserId(a), c.password(), c.code(), c.firebaseIdToken());
        return ApiResponse.success(null);
    }

    @PostMapping("/deactivate")
    public ApiResponse<?> deactivate(Authentication a, @Valid @RequestBody Credentials c) {
        service.close(SecurityUtils.currentUserId(a), c.password(), c.code(), false, c.firebaseIdToken());
        return ApiResponse.success(null);
    }

    @PostMapping("/delete")
    public ApiResponse<?> delete(Authentication a, @Valid @RequestBody Credentials c) {
        service.close(SecurityUtils.currentUserId(a), c.password(), c.code(), true, c.firebaseIdToken());
        return ApiResponse.success(null);
    }
}
