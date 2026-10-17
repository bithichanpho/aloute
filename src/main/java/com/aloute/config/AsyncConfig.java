package com.aloute.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class AsyncConfig {

    @Bean(name = "callTimeoutExecutor", destroyMethod = "shutdownNow")
    public ScheduledExecutorService callTimeoutExecutor() {
        return Executors.newScheduledThreadPool(
                2,
                r -> {
                    Thread t = new Thread(r, "call-timeout-");
                    t.setDaemon(true);
                    return t;
                });
    }

    @Bean(name = "moderationExecutor")
    public Executor moderationExecutor(
            @Value("${app.moderation.executor.core-size:8}") int coreSize,
            @Value("${app.moderation.executor.max-size:16}") int maxSize,
            @Value("${app.moderation.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int safeCore = Math.max(2, Math.min(32, coreSize));
        int safeMax = Math.max(safeCore, Math.min(64, maxSize));
        executor.setCorePoolSize(safeCore);
        executor.setMaxPoolSize(safeMax);
        executor.setQueueCapacity(Math.max(10, Math.min(2000, queueCapacity)));
        executor.setThreadNamePrefix("moderation-");
        // Keep enough workers active for concurrent text/media checks. The bounded queue limits
        // memory pressure; rejected jobs remain PENDING and the DB-backed scanner retries them.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean(name = "passwordMailExecutor")
    public Executor passwordMailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("password-mail-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notif-async-");
        executor.initialize();
        return executor;
    }
}
