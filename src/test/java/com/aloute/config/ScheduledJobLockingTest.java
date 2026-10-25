package com.aloute.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Guards against a new {@code @Scheduled} job silently running on every replica. A job either
 * takes a {@code @SchedulerLock} or is listed below together with the reason it is safe without.
 */
class ScheduledJobLockingTest {

    private static final Map<String, String> SAFE_WITHOUT_CLUSTER_LOCK =
            Map.of(
                    "AuthRateLimitService#cleanup", "prunes this node's in-memory fallback map",
                    "PresenceService#refreshLocalConnections", "refreshes this node's own Redis leases",
                    "CallTimeoutService#processDistributedTimeouts", "claims each timeout atomically in PostgreSQL with recoverable leases",
                    "PostModerationCoordinator#recoverPendingPosts", "claims work via a PostgreSQL lease",
                    "CommentModerationCoordinator#recoverPendingComments", "claims work via a PostgreSQL lease",
                    "AccountDataCleanupRetryWorker#retryDueJobs", "claims jobs via an atomic PostgreSQL lease");

    @Test
    void everyScheduledJobIsLockedOrExplicitlyAllowed() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        List<String> unlocked = new ArrayList<>();
        Set<String> seenAllowed = new HashSet<>();
        Set<String> lockNames = new HashSet<>();

        for (BeanDefinition definition : scanner.findCandidateComponents("com.aloute")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            for (Method method : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) continue;
                String id = type.getSimpleName() + "#" + method.getName();
                SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
                if (lock != null) {
                    assertTrue(lockNames.add(lock.name()), "Duplicate lock name " + lock.name());
                    assertFalse(
                            SAFE_WITHOUT_CLUSTER_LOCK.containsKey(id),
                            id + " is locked; remove it from the allowlist");
                } else if (SAFE_WITHOUT_CLUSTER_LOCK.containsKey(id)) {
                    seenAllowed.add(id);
                } else {
                    unlocked.add(id);
                }
            }
        }

        assertTrue(
                unlocked.isEmpty(),
                "@Scheduled jobs without @SchedulerLock would run on every replica: " + unlocked);
        assertEquals(
                SAFE_WITHOUT_CLUSTER_LOCK.keySet(),
                seenAllowed,
                "Allowlist contains a job that no longer exists");
    }
}
