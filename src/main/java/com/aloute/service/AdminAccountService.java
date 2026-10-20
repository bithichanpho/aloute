package com.aloute.service;

import com.aloute.common.exception.BadRequestException;
import com.aloute.common.exception.ForbiddenException;
import com.aloute.common.exception.ResourceNotFoundException;
import com.aloute.common.util.TransactionCommitHooks;
import com.aloute.dto.response.AdminAccountAuditResponse;
import com.aloute.dto.response.AdminAccountDetail;
import com.aloute.dto.response.AdminAccountSummary;
import com.aloute.dto.response.PagedResponse;
import com.aloute.model.entity.AccountAdminAudit;
import com.aloute.model.entity.User;
import com.aloute.repository.IAccountAdminAuditRepository;
import com.aloute.repository.IUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAccountService {
    private static final Set<String> PROTECTED_ROLES = Set.of("ROLE_ADMIN", "ROLE_HOST");

    private final IUserRepository users;
    private final IAccountAdminAuditRepository audits;
    private final RefreshTokenService refreshTokens;
    private final AuthenticationUserCache authenticationCache;

    public AdminAccountService(
            IUserRepository users,
            IAccountAdminAuditRepository audits,
            RefreshTokenService refreshTokens,
            AuthenticationUserCache authenticationCache) {
        this.users = users;
        this.audits = audits;
        this.refreshTokens = refreshTokens;
        this.authenticationCache = authenticationCache;
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminAccountSummary> list(String query, int requestedPage, int requestedSize) {
        int page = Math.max(0, Math.min(10_000, requestedPage));
        int size = Math.max(1, Math.min(100, requestedSize));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        String term = query == null ? "" : query.trim();
        Page<User> result = term.isBlank()
                ? users.findAll(pageable)
                : users.findByUsernameContainingIgnoreCaseOrFullNameContainingIgnoreCaseOrEmailContainingIgnoreCase(
                        term, term, term, pageable);
        return pageOf(result.map(this::summary));
    }

    @Transactional(readOnly = true)
    public AdminAccountDetail detail(Long userId) {
        User user = user(userId);
        List<AdminAccountAuditResponse> history = audits
                .findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 50))
                .stream().map(this::auditResponse).toList();
        return new AdminAccountDetail(summary(user), history.isEmpty() ? null : history.get(0), history);
    }

    @Transactional(readOnly = true)
    public PagedResponse<AdminAccountAuditResponse> history(Long userId, int page, int size) {
        user(userId);
        Page<AccountAdminAudit> result = audits.findByUserIdOrderByCreatedAtDescIdDesc(
                userId, PageRequest.of(Math.max(0, Math.min(10_000, page)),
                        Math.max(1, Math.min(100, size))));
        return pageOf(result.map(this::auditResponse));
    }

    @Transactional
    @CacheEvict(cacheNames = "hostDashboard", allEntries = true)
    public AdminAccountSummary lock(Long actorId, Long userId, String reason) {
        User target = mutableTarget(actorId, userId);
        if (!target.isActive() && !Boolean.TRUE.equals(target.getSelfDeactivated())) {
            throw new BadRequestException("Tài khoản đã bị khóa");
        }
        String normalizedReason = requiredReason(reason);
        Instant now = Instant.now();
        target.setActive(false);
        target.setSelfDeactivated(false);
        target.setTokensRevokedAt(now);
        users.save(target);
        refreshTokens.revokeAll(userId, now);
        audits.save(new AccountAdminAudit(userId, actorId, "LOCK", normalizedReason));
        invalidateAfterCommit(target.getUsername());
        return summary(target);
    }

    @Transactional
    @CacheEvict(cacheNames = "hostDashboard", allEntries = true)
    public AdminAccountSummary unlock(Long actorId, Long userId, String reason) {
        User target = mutableTarget(actorId, userId);
        if (target.isActive()) throw new BadRequestException("Tài khoản đang hoạt động");
        target.setActive(true);
        target.setSelfDeactivated(false);
        users.save(target);
        audits.save(new AccountAdminAudit(userId, actorId, "UNLOCK", optionalReason(reason,
                "Mở khóa bởi quản trị viên")));
        invalidateAfterCommit(target.getUsername());
        return summary(target);
    }

    @Transactional
    public void forceLogout(Long actorId, Long userId, String reason) {
        User target = mutableTarget(actorId, userId);
        Instant now = Instant.now();
        target.setTokensRevokedAt(now);
        users.save(target);
        refreshTokens.revokeAll(userId, now);
        audits.save(new AccountAdminAudit(userId, actorId, "FORCE_LOGOUT", optionalReason(reason,
                "Buộc đăng xuất bởi quản trị viên")));
        invalidateAfterCommit(target.getUsername());
    }

    private User mutableTarget(Long actorId, Long userId) {
        if (actorId == null) throw new ForbiddenException("Không xác định được quản trị viên");
        if (actorId.equals(userId)) {
            throw new BadRequestException("Không thể thực hiện thao tác quản trị trên chính mình");
        }
        User target = user(userId);
        String role = target.getRole() == null ? "" : target.getRole().getName();
        if (PROTECTED_ROLES.contains(role)) {
            throw new ForbiddenException("Không thể thay đổi tài khoản quản trị được bảo vệ");
        }
        return target;
    }

    private User user(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản"));
    }

    private String requiredReason(String reason) {
        String value = reason == null ? "" : reason.trim();
        if (value.length() < 5) throw new BadRequestException("Lý do khóa phải có ít nhất 5 ký tự");
        if (value.length() > 500) throw new BadRequestException("Lý do tối đa 500 ký tự");
        return value;
    }

    private String optionalReason(String reason, String fallback) {
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) return fallback;
        if (value.length() > 500) throw new BadRequestException("Lý do tối đa 500 ký tự");
        return value;
    }

    private AdminAccountSummary summary(User user) {
        return new AdminAccountSummary(
                user.getId(), user.getUsername(), user.getEmail(), user.getFullName(),
                user.getRole() == null ? null : user.getRole().getName(), user.isActive(),
                Boolean.TRUE.equals(user.getSelfDeactivated()), user.isEmailVerified(),
                user.getTokensRevokedAt(), user.getCreatedAt());
    }

    private AdminAccountAuditResponse auditResponse(AccountAdminAudit audit) {
        return new AdminAccountAuditResponse(audit.getId(), audit.getUserId(), audit.getActorId(),
                audit.getAction(), audit.getReason(), audit.getCreatedAt());
    }

    private <T> PagedResponse<T> pageOf(Page<T> page) {
        return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }

    private void invalidateAfterCommit(String username) {
        TransactionCommitHooks.afterCommit(() -> authenticationCache.invalidate(username));
    }
}
