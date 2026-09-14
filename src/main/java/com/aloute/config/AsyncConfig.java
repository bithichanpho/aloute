package com.aloute.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Thread pool riêng cho các tác vụ @Async (chủ yếu là NotificationService.notify).
 * Trước đây mỗi lượt like/comment/follow phải CHỜ xong việc tạo notification + đẩy
 * WebSocket rồi mới trả response cho client -> nút thả tym/bình luận có cảm giác chậm.
 * Giờ các tác vụ này chạy trên thread nền, API trả kết quả ngay sau khi ghi xong dữ liệu chính.
 */
@Configuration
public class AsyncConfig {

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
