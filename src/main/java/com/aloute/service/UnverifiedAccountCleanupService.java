package com.aloute.service;

import com.aloute.repository.IPendingUserRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UnverifiedAccountCleanupService {

    private static final Logger log =
            LoggerFactory.getLogger(UnverifiedAccountCleanupService.class);

    private final IPendingUserRepository pendingUserRepository;

    public UnverifiedAccountCleanupService(IPendingUserRepository pendingUserRepository) {
        this.pendingUserRepository = pendingUserRepository;
    }

    @Scheduled(
            initialDelayString = "${app.verification-cleanup.initial-delay-ms:600000}",
            fixedDelayString = "${app.verification-cleanup.fixed-delay-ms:3600000}")
    @SchedulerLock(name = "unverifiedAccountCleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void deleteExpiredAccounts() {
        long deleted = pendingUserRepository.deleteByExpiresAtLessThanEqual(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Đã xóa {} đăng ký chờ xác thực quá hạn", deleted);
        }
    }
}
