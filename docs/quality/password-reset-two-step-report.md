# Đặt lại mật khẩu: xác thực OTP trước khi mở trang mật khẩu

Ngày 25/10/2026. Phạm vi theo yêu cầu: phải đúng mã trước khi vào trang đặt mật khẩu mới.

## 1. Verified Architecture Snapshot

Source hiện tại dùng Spring Boot 4.1.1, target Java 21 (kiểm thử JDK 22), PostgreSQL JPA/JSONB, Redis, Thymeleaf và JS thuần. Đã đọc AGENTS.md và audit controller/service/repository, OTP, cookie, security matcher, no-cache, templates và tests. Workspace không có `.git`; không init/reset. Baseline gần nhất trước sửa: 38 Java tests và 60 cấu hình/ca browser trong checkpoint OTP trước đó.

`Confirmed Bug` về luồng UX: trang `/reset-password` cũ công khai cả OTP và mật khẩu cùng lúc. `Already Correct`: backend cũ vẫn kiểm tra OTP trước khi thay mật khẩu và dùng CAS để chỉ tiêu thụ một lần. Không báo cáo đây là lỗi cho phép đổi mật khẩu không cần mã. `Confirmed Risk`: gửi lại mã cũ dùng entity save có thể ghi đè trường khác khi concurrency; thay bằng update đúng các trường reset và cooldown trong database.

## 2. Module Migration Status

| Module | Thay đổi | Bằng chứng | Trạng thái |
|---|---|---|---|
| Identity | Luồng OTP → proof → mật khẩu; gate backend; atomic reset issuance | Unit, architecture, PostgreSQL, HTTP, browser | Implemented; kết quả cụ thể tại mục 6 |
| Users | Giữ aggregate; chỉ cập nhật reset fields qua repository owner hiện hữu | PostgreSQL integrity/concurrency | Không di chuyển schema/owner |
| Social, Posts/Feed, Communities, Messaging/Calls, Notifications, Media/Moderation, Administration | Không đổi implementation trong lượt này | Unit hiện hữu, FullBackendContextIT cho wiring/logout/feed | Không tuyên bố audit lại toàn bộ |

## 3. Dependency / Architecture Violations

`PasswordResetController` gọi `PasswordResetFlowService`, flow dùng `PasswordManagementService` và dịch vụ mã hóa hiện hữu. Không thêm hạ tầng, repository ngoài owner hoặc dependency vòng. Các public REST cũ giữ tương thích; module boundary tests hiện hữu tiếp tục chạy. Kiến trúc backend tổng thể vẫn PARTIAL ngoài phạm vi này.

## 4. Changed Files và Contract

- Mới `service/PasswordResetFlowService.java`: proof mã hóa AES-GCM có purpose/version, user ID, hash reset, thời hạn của đúng lần cấp mã và hạn phiên 5 phút. Dùng key mã hóa hiện hữu, không tự tạo/rotate key. Không lưu hash OTP dễ dò trong JWT đọc được.
- Mới `controller/PasswordResetController.java`: GET `/verify-reset-code`, gate GET `/reset-password`; POST `/api/auth/verify-reset-code` (`email`,`code`) và `/api/auth/complete-password-reset` (`newPassword`). Proof chỉ qua cookie HttpOnly, SameSite Strict, Path `/`, Secure theo cấu hình cookie hiện hữu; không trả token/hash trong JSON. Thành công đổi mật khẩu xóa cookie.
- Sửa `service/PasswordManagementService.java`, `repository/IUserRepository.java`: dùng lại kiểm tra OTP/expiry/CAS cuối; immutable proof nội bộ; cấp OTP atomic để tránh stale entity save và gửi mail sau commit. Kiểm tra thời hạn tại CAS sau BCrypt, vẫn revoke refresh sessions và invalidation sau commit.
- Sửa `controller/ViewController.java`: chuyển mapping trang reset sang controller có gate; `config/SecurityConfig.java`, `config/NoCacheViewConfig.java`: thêm trang nhập mã vào matcher công khai và no-store.
- Mới `templates/auth/verify-reset-code.html`, `static/js/auth-verify-reset-code.js`: chỉ email và sáu ô OTP, giữ hiệu ứng #026, sai mã không điều hướng.
- Sửa `templates/auth/reset-password.html`, `static/js/auth-reset-password.js`: chỉ hai ô mật khẩu, không nhận email/OTP từ URL/browser storage; phiên hết hạn ẩn form, có link xác thực lại; reload khi khôi phục trang từ history.
- Sửa `static/js/auth-forgot-password.js`, `templates/auth/forgot-password.html`: đi đến trang nhập mã sau ACK trung tính, version cache `20261025-reset-v1`.
- Tests mới/sửa: `PasswordResetFlowServiceTest`, `PasswordResetControllerTest`, `PasswordResetFlowIT`, `PasswordManagementServiceTest`, `AuthTemplateCspTest`, `GlobalThemeTemplateTest`, `src/test/browser/password-reset-flow.cjs`, `src/test/browser/auth-error-focus.cjs`. Các assertion OTP chuyển sang trang xác thực mới; password policy được giữ và kiểm tra trong luồng hai bước.

Đường dẫn Java ở trên dưới `src/main/java/com/aloute/`, tài nguyên dưới `src/main/resources/`, tests dưới `src/test/java/com/aloute/`. Không có migration, không xóa/đổi format dữ liệu. Endpoint cũ POST `/api/auth/reset-password` (`email`,`code`,`newPassword`) vẫn kiểm tra mã đúng và phục vụ client cũ; không cho phép ghi mật khẩu với mã sai hoặc sau khi token bị tiêu thụ.

## 5. Security and Business Rules Preserved

Nhập mã đúng cấp proof; chưa đổi mật khẩu và chưa tiêu thụ OTP. Mọi GET trang mật khẩu và POST hoàn tất kiểm tra cả proof lẫn reset hash/expiry hiện tại trong PostgreSQL. Gửi lại mã, hết hạn hoặc hoàn tất bằng bất kỳ đường tương thích nào làm proof cũ không dùng được. Dù xác thực đúng nhiều lần tạo nhiều proof, CAS chỉ cho một lần đổi mật khẩu thành công. Proof gắn với account và lần cấp mã server-side; bước cuối không tin email/userId từ client. CAS mới so sánh chính xác cả hash và expiry gốc, chặn phiên cũ sống lại khi OTP sáu số trùng ở lần gửi sau, kể cả race xảy ra sau bước đọc.

Không mở trang mật khẩu chỉ bằng query email hoặc cờ JS. Không lưu mã/proof vào URL, sessionStorage/localStorage. CSRF áp dụng các POST mới. Verify dùng chung `RESET_OTP_TARGET` và `RESET_OTP_IP` với API cũ, tránh thêm ngân sách dò mã khi đổi endpoint; complete có giới hạn IP riêng. Mail request vẫn trả thông báo trung tính. Giữ password Unicode/72 byte, revocation, cache và quyền nghiệp vụ ngoài auth. Các key cấu hình production không bị đọc/in/sao chép.

## 6. Test Results

Kết quả **lượt verify cuối**, sau bảo vệ theo từng lần cấp mã; không cộng trùng các bài đã chạy lặp:

| Suite | Passed | Failed/Error | Skipped | Ghi chú |
|---|---:|---:|---:|---|
| Toàn bộ unit tests | 419 | 0 | 1 | Bao gồm 12 module-boundary tests; AlouteApplicationTests skip theo DB_URL |
| PasswordResetFlowIT | 6 | 0 | 0 | PostgreSQL, CSRF, HTTP cookie thật, concurrency, OTP trùng |
| FullBackendContextIT + RegistrationBusinessIT | 4 | 0 | 0 | Wiring, Feed/logout và đăng ký |
| Browser hai bước | 8 | 0 | 0 | Sáng/tối, desktop/mobile, normal/reduced motion |
| Browser hồi quy auth | 32 | 0 | 0 | Login, đăng ký OTP, forgot và OTP reset mới |

Maven `BUILD SUCCESS`, 3 phút 37 giây. Đây là toàn bộ unit và **ba integration suites được chọn**, không phải toàn bộ IT của backend. Ca HTTP thật xác nhận cookie AES-GCM có thể đi qua Tomcat và bị từ chối khi sửa giá trị. Đã đối chiếu SHA-256 16 entry tài nguyên/class trong JAR với source/class sau compile. Browser chạy trên tài nguyên cuối, không đổi UI sau khi test. Đã dừng đúng PostgreSQL/Redis do tác vụ sở hữu sau kiểm thử, giữ database test và không chạm instance ứng dụng người dùng.

```powershell
mvn -B '-Dit.test=PasswordResetFlowIT,FullBackendContextIT,RegistrationBusinessIT' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/reset-two-step-20261025-013142/temp -Duser.timezone=UTC' verify
mvn -B '-Dtest=PasswordResetFlowServiceTest,PasswordManagementServiceTest,PasswordResetControllerTest,AuthControllerWebTest,ModuleBoundaryTest' '-Dit.test=PasswordResetFlowIT' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/reset-two-step-20261025-013142/temp -Duser.timezone=UTC' verify
node src/test/browser/password-reset-flow.cjs
node src/test/browser/auth-error-focus.cjs
```

Test PostgreSQL chỉ dùng loopback `aloute_quality_datetime_20261024`, Redis 56379 và tài khoản giả; mail được mock. Fixtures reset có ID và tên `reset-it-*` riêng, cleanup chỉ những row do test tạo. Tests kiểm tra CSRF, bypass GET/POST, OTP sai, cookie HttpOnly, replay, reset hash đổi/hết hạn, revoke refresh, concurrency chỉ một winner, atomic issuance không ghi đè password mới. Browser mới dùng HTTP fixture server localhost thực để kiểm tra redirect/cookie và UI; không phải E2E với SMTP thật. Hồi quy browser cũ tiếp tục dùng fixtures cách ly.

Lần compile test đầu gặp overload assertEquals không rõ kiểu, đã sửa unboxing trong test; lần browser fixture đầu gặp DNS khi redirect địa chỉ giả, đã đổi fixture sang server loopback. Test HTTP đầu cần chấp nhận redirect tuyệt đối do Tomcat tạo: resolve URI và vẫn kiểm tra chính xác cùng origin/đường dẫn, không nới phép redirect sang host khác. Không đổi logic production để né các lỗi fixture này. Sau đó bổ sung bảo vệ theo từng lần cấp OTP và regression SQL cho mã trùng. Log các lần đầu vẫn giữ trong checkpoint, không ghi là PASS.

## 7. Performance

Không có số đo p95/throughput; không tuyên bố cải thiện hiệu năng. Lookup proof dùng primary key, không thêm index/read model hoặc phân trang RAM. Bảo toàn format hash reset 64 hex trong cột hiện hữu.

## 8. Remaining Issues và Rollback

Chưa kiểm chứng SMTP/TOTP provider, thiết bị điện thoại/screen reader thật, BFCache thực hoặc nhiều instance chạy đồng thời. Mã OTP hiện hữu và proof đều phụ thuộc thời hạn backend; mọi instance phải dùng key mã hóa cấu hình thống nhất như dịch vụ mã hóa hiện tại. Không triển khai production, rotate key hay chạy migration.

Backup trước sửa: `D:/aloute-develop123/aloute-quality-checkpoints/reset-two-step-20261025-013142/`, 13 file trong `before.json`. Rollback đúng file sau khi đối chiếu chỉnh sửa phát sinh, bỏ consumer/tài nguyên mới, build lại; giữ dữ liệu mật khẩu đã thay và trạng thái reset hiện hữu. Không reset repo/DB. Cookie mới hết hạn sau 5 phút, phiên bản cũ có thể bỏ qua cookie và dùng API OTP tương thích. Mail dispatcher cũ vẫn không phải durable outbox; không tuyên bố bảo đảm delivery từ thay đổi này.

## 9. Final Assessment

**DONE trong phạm vi yêu cầu luồng đặt lại mật khẩu hai bước**: code, Spring wiring, bảo vệ backend, rollback và các bằng chứng tại mục 6 đã có. Các tích hợp/thiết bị tại mục 8 chưa kiểm chứng; không tuyên bố hoàn thành mục tiêu refactor toàn bộ backend. Báo cáo và bằng chứng ở `docs/quality/password-reset-two-step-evidence/`, log đầy đủ và backup ngoài repository.
