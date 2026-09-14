package com.aloute.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "callTimeoutExecutor", destroyMethod = "shutdownNow")
    public ScheduledExecutorService callTimeoutExecutor() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "call-timeout-");
            t.setDaemon(true);
            return t;
        });
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
