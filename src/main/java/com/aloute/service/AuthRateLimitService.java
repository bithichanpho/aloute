package com.aloute.service;

import com.aloute.common.exception.TooManyRequestsException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {

    // Cho phép tối đa 5 request trong 15 phút
    private static final int MAX_REQUESTS = 5;

    private static final Duration WINDOW =
            Duration.ofMinutes(15);

    // Spam quá mức → khóa chức năng gửi mail 30 phút
    private static final Duration BLOCK_DURATION =
            Duration.ofMinutes(30);

    private final Map<String, RateState> states =
            new ConcurrentHashMap<>();

    /**
     * Hai chức năng forgot-password và resend-verification
     * dùng chung quota chống spam (giữ nguyên hành vi cũ).
     */
    public void checkAndRecord(String ipAddress) {
        checkAndRecord("AUTH_MAIL", ipAddress, MAX_REQUESTS, WINDOW, BLOCK_DURATION,
                "Bạn đã yêu cầu gửi email quá nhiều lần. Chức năng này tạm khóa khoảng %d phút.",
                "Bạn đã gửi yêu cầu quá nhiều lần. Chức năng gửi email đã bị tạm khóa 30 phút.");
    }

    /**
     * Bản dùng chung, có thể cấu hình riêng ngưỡng/thời gian cho từng loại hành động —
     * dùng cho login (chống brute-force đoán mật khẩu), register (chống tạo tài khoản
     * hàng loạt), đăng bài/bình luận (chống spam nội dung)...
     *
     * @param namespace     tên nhóm hành động, vd "LOGIN", "REGISTER", "POST" — tách quota
     *                      của các hành động khác nhau dù cùng 1 IP hoặc user.
     * @param key           định danh chủ thể bị giới hạn: IP (login/register, vì chưa có user)
     *                      hoặc userId dạng chuỗi (đăng bài/bình luận, vì đã xác thực).
     * @param maxRequests   số request tối đa được phép trong 1 cửa sổ thời gian.
     * @param window        độ dài cửa sổ đếm request.
     * @param blockDuration thời gian khóa nếu vượt quá maxRequests.
     * @param blockedMsgFmt thông báo khi ĐANG bị khóa, chứa 1 placeholder %d cho số phút còn lại.
     * @param triggerMsg    thông báo khi request NÀY làm kích hoạt khóa.
     */
    public void checkAndRecord(String namespace, String subjectKey, int maxRequests, Duration window,
            Duration blockDuration, String blockedMsgFmt, String triggerMsg) {

        String subject = normalizeIp(subjectKey);
        String key = namespace + ":" + subject;

        RateState state = states.computeIfAbsent(
                key,
                ignored -> new RateState(LocalDateTime.now())
        );

        synchronized (state) {

            LocalDateTime now = LocalDateTime.now();

            // Chủ thể đang bị khóa
            if (state.blockedUntil != null
                    && now.isBefore(state.blockedUntil)) {

                long seconds = Duration.between(
                        now,
                        state.blockedUntil
                ).getSeconds();

                long minutes =
                        Math.max(1, (seconds + 59) / 60);

                throw new TooManyRequestsException(String.format(blockedMsgFmt, minutes));
            }

            // Hết thời gian khóa → reset
            if (state.blockedUntil != null
                    && !now.isBefore(state.blockedUntil)) {

                state.blockedUntil = null;
                state.windowStart = now;
                state.requestCount = 0;
            }

            // Sang cửa sổ thời gian mới
            if (now.isAfter(
                    state.windowStart.plus(window))) {

                state.windowStart = now;
                state.requestCount = 0;
            }

            /*
             * Đã dùng đủ số lần cho phép.
             * Request tiếp theo sẽ kích hoạt khóa.
             */
            if (state.requestCount >= maxRequests) {

                state.blockedUntil =
                        now.plus(blockDuration);

                throw new TooManyRequestsException(triggerMsg);
            }

            state.requestCount++;
        }
    }

    /**
     * Kiểm tra xem chủ thể có đang bị khóa hay không, KHÔNG tăng bộ đếm request.
     * Dùng để chặn sớm trước khi thử (vd: chặn login ngay từ đầu nếu IP đang bị khóa,
     * thay vì tốn công verify mật khẩu rồi mới biết là bị khóa).
     */
    public void rejectIfBlocked(String namespace, String subjectKey, String blockedMsgFmt) {
        String key = namespace + ":" + normalizeIp(subjectKey);
        RateState state = states.get(key);
        if (state == null) return;

        synchronized (state) {
            LocalDateTime now = LocalDateTime.now();
            if (state.blockedUntil != null && now.isBefore(state.blockedUntil)) {
                long seconds = Duration.between(now, state.blockedUntil).getSeconds();
                long minutes = Math.max(1, (seconds + 59) / 60);
                throw new TooManyRequestsException(String.format(blockedMsgFmt, minutes));
            }
        }
    }

    /**
     * Xóa toàn bộ trạng thái đếm/khóa của 1 chủ thể — gọi khi hành động đã THÀNH CÔNG
     * (vd đăng nhập đúng mật khẩu), để những lần sai trước đó không cộng dồn mãi vào
     * tài khoản/IP đang dùng hợp lệ.
     */
    public void reset(String namespace, String subjectKey) {
        states.remove(namespace + ":" + normalizeIp(subjectKey));
    }

    /**
     * Dọn IP cũ để map không tăng mãi.
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanup() {

        LocalDateTime now = LocalDateTime.now();

        states.entrySet().removeIf(entry -> {

            RateState state = entry.getValue();

            synchronized (state) {

                if (state.blockedUntil != null
                        && now.isBefore(state.blockedUntil)) {
                    return false;
                }

                return now.isAfter(
                        state.windowStart
                                .plus(WINDOW)
                                .plus(BLOCK_DURATION)
                );
            }
        });
    }

    private String normalizeIp(String ip) {

        if (ip == null || ip.isBlank()) {
            return "unknown";
        }

        return ip.trim();
    }

    private static class RateState {

        private LocalDateTime windowStart;

        private int requestCount;

        private LocalDateTime blockedUntil;

        private RateState(LocalDateTime windowStart) {
            this.windowStart = windowStart;
        }
    }
}