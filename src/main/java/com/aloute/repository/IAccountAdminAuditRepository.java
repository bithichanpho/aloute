package com.aloute.repository;

import com.aloute.model.entity.AccountAdminAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IAccountAdminAuditRepository extends JpaRepository<AccountAdminAudit, Long> {
    Page<AccountAdminAudit> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
}
