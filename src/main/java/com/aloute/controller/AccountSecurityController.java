package com.aloute.controller;

import com.aloute.common.response.ApiResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AccountSecurityService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account")
@lombok.RequiredArgsConstructor
public class AccountSecurityController {
    private final AccountSecurityService service;

    public record Credentials(@NotBlank String password, String code) {}

    @GetMapping("/2fa")
    public ApiResponse<?> status(Authentication a) {
        return ApiResponse.success(
                java.util.Map.of("enabled", service.enabled(SecurityUtils.currentUserId(a))));
    }

    @PostMapping("/2fa/setup")
    public ApiResponse<?> setup(Authentication a, @Valid @RequestBody Credentials c) {
        return ApiResponse.success(service.setup(SecurityUtils.currentUserId(a), c.password()));
    }

    @PostMapping("/2fa/enable")
    public ApiResponse<?> enable(Authentication a, @Valid @RequestBody Credentials c) {
        return ApiResponse.success(
                service.enable(SecurityUtils.currentUserId(a), c.password(), c.code()));
    }

    @PostMapping("/2fa/disable")
    public ApiResponse<?> disable(Authentication a, @Valid @RequestBody Credentials c) {
        service.disable(SecurityUtils.currentUserId(a), c.password(), c.code());
        return ApiResponse.success(null);
    }

    @PostMapping("/deactivate")
    public ApiResponse<?> deactivate(Authentication a, @Valid @RequestBody Credentials c) {
        service.close(SecurityUtils.currentUserId(a), c.password(), c.code(), false);
        return ApiResponse.success(null);
    }

    @PostMapping("/delete")
    public ApiResponse<?> delete(Authentication a, @Valid @RequestBody Credentials c) {
        service.close(SecurityUtils.currentUserId(a), c.password(), c.code(), true);
        return ApiResponse.success(null);
    }
}
