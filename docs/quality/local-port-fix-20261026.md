# Sửa cổng local 8081 — 26/10/2026

## Confirmed Bug

Log người dùng cung cấp lúc 12:03 chạy từ `target/classes`, profile default, nhưng Tomcat lấy cổng 8080. `application.yml` và bản sao resources trong target đều đặt 8081. Nguồn ghi đè là thuộc tính không nhạy cảm `server.port=8080` trong `.env` được import bởi application.yml. Không kết luận JAR cũ là nguyên nhân của lần chạy IDE này.

Cổng 8080 đang được PID 10680 (`AgentService.exe`) dùng khi kiểm tra. Không dừng tiến trình đó. JAR trước sửa cũng còn đóng gói cổng 8080 vì được tạo trước khi người dùng sửa application.yml.

## Implemented

- Bỏ dòng cổng trùng trong `.env`, để `server.port` lấy từ application.yml (8081).
- Sửa localhost:8080 thành localhost:8081 chỉ trên hai cấu hình public `FRONTEND_BASE_URL` và `APP_CORS_ALLOWED_ORIGINS` trong `.env`; giữ các giá trị khác nguyên trạng, không đưa secrets vào báo cáo.
- Thêm `app.cors.allowed-origins` vào application.yml, lấy từ cấu hình origins hiện có hoặc localhost với server.port. HTTP CORS và WebSocket dùng chung property này. Không đổi Docker/nginx/production profile.
- Build source hiện tại vướng test JSON duration thiếu getter; bổ sung lại trường nullable/getter/setter `ChatMessage.callDurationSeconds` để bảo toàn document cũ và bỏ blocker testCompile.
- Hai thư viện vendored SockJS/STOMP có CRLF; khôi phục LF sau khi xác nhận SHA-256 chuẩn hóa khớp chính xác digest đã pin. Không đổi phiên bản hay nội dung JavaScript.

## Tested

- Probe dùng Spring ConfigDataEnvironmentPostProcessor để nạp cấu hình thật, không khởi tạo application context/database: effective server.port=8081, nguồn là application.yml; frontend và CORS khớp localhost:8081.
- Compile main 332 source files và testCompile 125 source files trên source hiện tại: PASS. Snapshot source này khác lượt kiểm tra trước; không lấy tổng test cũ làm kết quả cho lần này.
- `mvn -o -B -Dtest=AllowedOriginPolicyTest,WebSocketConfigTest,AuthTemplateCspTest,RealtimeDependencyTest,ChatMessageJsonCompatibilityTest package`: BUILD SUCCESS; 8 tests PASS, 0 failures/errors/skips.
- Log: target/port-fix-package-20261026-verified.log. Artifact mới: target/aloute-0.0.1-SNAPSHOT.jar.
- Chưa khởi động backend với database thật; cần chạy lại tiến trình IDE để nạp cấu hình mới. Không chạy migration hoặc thay đổi dữ liệu.

Rollback cấu hình: điều chỉnh application.yml và các public URL local đồng bộ với cổng mong muốn; tránh khai báo lại server.port cũ trong file import. Không cần rollback schema.
