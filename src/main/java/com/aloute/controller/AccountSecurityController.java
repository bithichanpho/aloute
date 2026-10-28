package com.aloute.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import com.aloute.common.response.ApiResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AccountSecurityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Sensitive account lifecycle actions. The former 2FA setup/status endpoints have been removed. */
@RestController
@SecurityRequirement(name = "accessCookie")
@RequestMapping("/api/account")
public class AccountSecurityController {
    private final AccountSecurityService service;

    public AccountSecurityController(AccountSecurityService service) { this.service = service; }

    public record Credentials(
            @NotBlank @Size(max = 200) String password,
            @Size(max = 200) String confirmPassword) {}

    @PostMapping("/delete")
    public ApiResponse<?> delete(Authentication a, @Valid @RequestBody Credentials c) {
        service.deleteAccount(SecurityUtils.currentUserId(a), c.password(), c.confirmPassword());
        return ApiResponse.success(null);
    }
}
