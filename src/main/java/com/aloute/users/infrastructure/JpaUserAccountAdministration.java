package com.aloute.users.infrastructure;

import com.aloute.common.exception.*;
import com.aloute.repository.IUserRepository;
import com.aloute.users.api.UserAccountAdministration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class JpaUserAccountAdministration implements UserAccountAdministration {
    private final IUserRepository users;

    public JpaUserAccountAdministration(IUserRepository users) { this.users = users; }

    public void requireAdministrator(Long actorId) {
        if (actorId == null || users.findById(actorId)
                .filter(user -> user.isActive() && user.getRole() != null
                        && "ROLE_ADMIN".equals(user.getRole().getName())).isEmpty()) {
            throw new ForbiddenException("Chỉ quản trị viên xử lý báo cáo được truy cập");
        }
    }

    public AccountReview reviewAccount(Long actorId, Long targetId) {
        requireAdministrator(actorId);
        var target = users.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản"));
        return new AccountReview(target.getUsername(), target.getFullName(), target.isActive());
    }

    public ChangedAccount setHiddenByAdministrator(Long actorId, Long targetId, boolean hidden) {
        requireAdministrator(actorId);
        var target = users.findById(targetId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy tài khoản"));
        if (target.getId().equals(actorId) || target.getRole() == null
                || java.util.Set.of("ROLE_ADMIN", "ROLE_HOST").contains(target.getRole().getName())) {
            throw new BadRequestException("Không thể khóa tài khoản quản trị được bảo vệ");
        }
        Instant revokedAt = Instant.now();
        target.setActive(!hidden);
        target.setSelfDeactivated(false);
        target.setTokensRevokedAt(revokedAt);
        users.save(target);
        return new ChangedAccount(target.getId(), target.getUsername(), revokedAt);
    }
}
