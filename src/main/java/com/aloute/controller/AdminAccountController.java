package com.aloute.controller;

import com.aloute.common.exception.UnauthorizedException;
import com.aloute.common.response.ApiResponse;
import com.aloute.dto.response.AdminAccountAuditResponse;
import com.aloute.dto.response.AdminAccountDetail;
import com.aloute.dto.response.AdminAccountSummary;
import com.aloute.dto.response.PagedResponse;
import com.aloute.security.SecurityUtils;
import com.aloute.service.AdminAccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {
    private final AdminAccountService accounts;

    public AdminAccountController(AdminAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public ApiResponse<PagedResponse<AdminAccountSummary>> list(
            @RequestParam(defaultValue = "") @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(accounts.list(q, page, size));
    }

    @GetMapping("/{userId}")
    public ApiResponse<AdminAccountDetail> detail(@PathVariable @Positive Long userId) {
        return ApiResponse.success(accounts.detail(userId));
    }

    @GetMapping("/{userId}/history")
    public ApiResponse<PagedResponse<AdminAccountAuditResponse>> history(
            @PathVariable @Positive Long userId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(accounts.history(userId, page, size));
    }

    @PostMapping("/{userId}/lock")
    public ApiResponse<AdminAccountSummary> lock(
            Authentication authentication,
            @PathVariable @Positive Long userId,
            @Valid @RequestBody AccountActionRequest request) {
        return ApiResponse.success("Đã khóa tài khoản",
                accounts.lock(actor(authentication), userId, request.reason()));
    }

    @PostMapping("/{userId}/unlock")
    public ApiResponse<AdminAccountSummary> unlock(
            Authentication authentication,
            @PathVariable @Positive Long userId,
            @Valid @RequestBody(required = false) AccountActionRequest request) {
        return ApiResponse.success("Đã mở khóa tài khoản", accounts.unlock(
                actor(authentication), userId, request == null ? null : request.reason()));
    }

    @PostMapping("/{userId}/force-logout")
    public ApiResponse<Void> forceLogout(
            Authentication authentication,
            @PathVariable @Positive Long userId,
            @Valid @RequestBody(required = false) AccountActionRequest request) {
        accounts.forceLogout(actor(authentication), userId, request == null ? null : request.reason());
        return ApiResponse.success("Đã buộc đăng xuất toàn bộ phiên", null);
    }

    public record AccountActionRequest(@Size(max = 500) String reason) {}

    private Long actor(Authentication authentication) {
        Long id = SecurityUtils.currentUserId(authentication);
        if (id == null) throw new UnauthorizedException("Cần đăng nhập để quản trị tài khoản");
        return id;
    }
}
