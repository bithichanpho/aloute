# Hiệu ứng nhập OTP sáu ô — prompt #026

Ngày 25/10/2026. Áp dụng theo mẫu Lucian UX/UI #026 người dùng đính kèm, giữ attribution MIT ở đầu component JS/CSS.

## 1. Verified Architecture Snapshot

Trang auth hiện tại dùng Thymeleaf, JavaScript thuần, CSS tokens và `data-bs-theme`; không dùng React hoặc thay framework. Backend Spring Boot 4.1.1, Java target 21; build bằng JDK 22. Các ô được thay ở `auth/verify.html` (OTP đăng ký), `auth/reset-password.html` và `auth/login.html` (2FA). Trang đăng ký điều hướng sang verify hiện hữu. Hai input bảo mật trong `features/index.html` thuộc màn hình cài đặt tài khoản, không thay trong phạm vi các luồng auth đang chỉnh.

Đã đọc AGENTS.md, khảo sát input/consumer/theme và kiểm tra Git: workspace không có `.git`. Baseline liên quan ngay trước lượt này: 38 Java tests và 32 cấu hình browser đạt, ghi trong checkpoint `login-generic-error-20261025-004623`; không tính lại chúng thành kết quả mới.

## 2. Module Migration Status

Identity UI: component OTP thật có ba consumer. Không thay ownership hoặc persistence. Các module Users, Social, Posts, Feed, Communities, Messaging/Calls, Notifications, Media/Moderation, Administration giữ nguyên mã trong lượt này. Kiến trúc tổng thể tiếp tục PARTIAL, không nghiệm thu toàn bộ backend từ sửa đổi giao diện này.

## 3. Dependency / Architecture Violations

Không thêm dependency hay hạ tầng. Component `AlouteOtp` nhận input thật và cung cấp trạng thái kiểm tra/thành công/thất bại cho adapter trang. CSS chỉ trong `.u026`, keyframes có prefix `u026-`; không đổi CSS/script toàn cục. Helper báo lỗi trước đó giữ nguyên.

## 4. Changed Files

- Mới `src/main/resources/static/js/auth-otp.js`: sáu ô trình bày quanh một input thật; cursor, số bật lên, paste, selection, Backspace/Arrow, trạng thái server và chế độ recovery cho login.
- Mới `src/main/resources/static/css/auth-otp.css`: hình hộp, khoảng cách, pop `.4s`, wave `.5s` lệch `60ms`, shake `.45s`, các curve theo mẫu; thu hẹp ô để vừa mobile, mapped colors theo theme và tắt animation khi reduced motion.
- Sửa `src/main/resources/static/js/auth-{login,verify,reset-password}.js`: nối trạng thái với POST/response thật, giữ busy/single-flight trong hiệu ứng, reset state khi khôi phục trang.
- Sửa `src/main/resources/templates/auth/{login,verify,reset-password}.html`: nạp external CSS/JS và cache version `20261025-otp-v1`; không đổi input IDs/names/constraints/labels/autocomplete.
- Sửa `src/test/browser/auth-error-focus.cjs`: thêm ma trận OTP và cập nhật regression theo yêu cầu xóa số sau mã sai, có nhập lại trước lần gửi kế tiếp. Đây là thay đổi hành vi có chủ đích từ mẫu, không bỏ assertions để test qua.
- Báo cáo và thư mục bằng chứng kèm theo. Không xóa file, không migration, không thay API/schema/events.

## 5. Security and Business Rules Preserved

Không mang mã demo `482915`, bộ đếm giả, số điện thoại mẫu hay cơ chế tự xác nhận client vào production. Component không tự POST khi đủ sáu số: vẫn gửi bằng form hiện hữu để giữ validation email/password/confirmation và tránh tiêu tốn lượt thử OTP khi chưa hoàn tất form. Wave chỉ khi thực sự gửi; xanh chỉ sau server ACK. Có thời gian trình bày tối thiểu 640ms (reduced: 60ms); lỗi mã rung và xóa hàng sau 900ms (reduced: 600ms), giữ email/password, focus ô đầu. Khóa input đến hết hiệu ứng để phản hồi cũ không xóa dữ liệu vừa nhập lại. Lỗi mạng/rate/timeout không xóa mã hay báo mã sai.

Login còn đầy đủ mã khôi phục 64 ký tự, chuyển qua input chữ bằng nút riêng hoặc khi nhận chuỗi recovery. Không lọc recovery thành số, không xóa recovery khi sai. Lỗi credentials vẫn đánh dấu cả username/password bằng một thông báo chung. Input thật còn `name=code`, autocomplete, accessible label và `aria-describedby`; sáu ô phụ `aria-hidden`, trạng thái có `role=status`. CSRF, payload, thời hạn và single-use của OTP do backend quyết định. Không đổi privacy, chat/call state, media retention hay quyền dữ liệu của module khác.

## 6. Test Results

| Suite | Passed | Failed/Error | Skipped | Ghi chú |
|---|---:|---:|---:|---|
| Java: 6 suites nêu dưới | 38 | 0 | 0 | Gồm 12 architecture tests |
| Browser hồi quy auth | 32 | 0 | 0 | 4 trang × theme × viewport × motion |
| Browser hiệu ứng OTP | 24 | 0 | 0 | 3 trang × theme × viewport × motion |
| Browser độ ổn định | 4 | 0 | 0 | Timeout/network, single-flight, retry, phản hồi muộn |
| PostgreSQL/SMTP/thiết bị thật | — | — | — | NOT RUN trong lượt này |

Maven `BUILD SUCCESS` trong 1 phút 49 giây; 8 tài nguyên runtime trong JAR khớp SHA-256 source cuối. Kiểm tra độ ổn định rút riêng deadline 30 giây xuống 180ms trong test, không thay deadline runtime. Các lệnh thực chạy của lượt này:

```powershell
node src/test/browser/auth-error-focus.cjs
node src/test/browser/auth-error-focus.cjs otp
node src/test/browser/auth-error-focus.cjs reliability
mvn -B '-Dtest=AuthTemplateCspTest,AuthControllerWebTest,AuthServiceVerifyEmailTest,PasswordManagementServiceTest,AccountSecurityServiceTest,ModuleBoundaryTest' '-DskipITs' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/otp-motion-20261025-005315/temp -Duser.timezone=UTC' verify
```

Browser dùng Edge headless với templates/scripts/styles thật, HTTP fixtures và tài khoản giả. Mã demo từ prompt bị fixture server từ chối, mã khác được ACK để kiểm tra backend là nguồn quyết định; giữ response đang chờ hơn 640ms xác nhận không tự bật xanh. Kiểm tra sáng/tối, 320/1280px, reduced motion, paste `482 915`, Backspace/Arrow, nhập lại sau lỗi, recovery, khóa và gửi trùng, CSRF/body/redirect, labels/aria và overflow. Có xem ảnh desktop tối và mobile sáng. Không coi đây là browser end-to-end với SMTP/TOTP thật.

## 7. Performance

Không đo throughput/p95. Không tuyên bố tăng hiệu năng hoặc độ an toàn backend từ hiệu ứng UI.

## 8. Remaining Issues và Rollback

Integration database và toàn bộ backend regression không chạy lại trong lượt UI (`-DskipITs`). Mail/TOTP provider thật, bàn phím điện thoại/SMS autofill và screen reader trên thiết bị thật chưa kiểm chứng; giữ các thuộc tính native hỗ trợ chúng. Chưa deploy/restart ứng dụng hoặc tác động database production.

Backup trước sửa: `D:/aloute-develop123/aloute-quality-checkpoints/otp-motion-20261025-005315/`, có `before.json` và 7 file gốc. Khôi phục đúng file sau khi so sánh chỉnh sửa phát sinh, bỏ hai tài nguyên OTP mới nếu hết consumer, build lại; không reset toàn bộ repository hoặc dữ liệu. Input/adapter fallback vẫn dùng được nếu component không tải, không yêu cầu database rollback.

## 9. Final Assessment

**DONE trong phạm vi thay hiệu ứng OTP của ba trang auth**: implementation có consumer thật, 38 Java tests, 32 cấu hình hồi quy auth, 24 cấu hình OTP và 4 ca độ ổn định đã đạt, build thành công. Không tuyên bố đã kiểm chứng toàn bộ backend hoặc các tích hợp/thiết bị thật tại mục 8. Bằng chứng ở `docs/quality/otp-motion-evidence/`; log đầy đủ và backup gốc ở checkpoint ngoài repository.
