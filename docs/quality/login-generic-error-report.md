# Lỗi đăng nhập chung cho cả cặp thông tin

Ngày 25/10/2026. Báo cáo bổ sung cho `auth-error-focus-report.md`; thay thế hành vi chỉ đánh dấu mật khẩu của phiên bản `focus-v1`.

## 1. Verified Architecture Snapshot

Phạm vi: Thymeleaf/JavaScript auth của repository hiện tại, Spring Boot 4.1.1, Java target 21 (test bằng JDK 22). Không đổi backend, PostgreSQL, Redis hay WebSocket. Đã đọc AGENTS.md, kiểm tra Git (workspace không có `.git`) và lưu backup trước sửa. `Confirmed Bug` theo yêu cầu UX mới: lỗi credentials chung lại chỉ gắn với ô mật khẩu.

## 2. Module Migration Status

Identity UI: thêm phản hồi lỗi cho cặp credentials. Users/Social/Posts/Feed/Communities/Messaging/Calls/Notifications/Media/Moderation/Administration giữ nguyên trong lượt này; không có migration module hay dữ liệu.

## 3. Dependency / Architecture Violations

Không thêm dependency hay truy cập persistence. Helper auth có consumer thực ở login; các consumer OTP tiếp tục dùng lỗi từng trường như trước.

## 4. Changed Files

- `static/js/auth-form-feedback.js`: thêm `showGroup`; hai trường chia sẻ một thông báo accessible, không render danh sách hay hint riêng. Sửa một trường sẽ xóa lỗi của cả cặp, tránh ngụ ý trường còn lại đã được xác nhận đúng.
- `static/js/auth-login.js`: thông báo `Sai tên đăng nhập hoặc mật khẩu` đánh dấu cả username/password, focus/select username. MFA sai vẫn focus code; rate/network error vẫn ở summary.
- `templates/auth/{login,verify,forgot-password,reset-password}.html`: cache version helper `20261025-credentials-v2`, login script cùng version; không thay fields hay API.
- `src/test/browser/auth-error-focus.cjs`: thay expectation mật khẩu riêng bằng lỗi chung, kiểm tra cả hai `aria-invalid`, summary không có dòng “Mật khẩu”, description chung, Tab đến password, sửa từng ô xóa lỗi cả cặp, và lỗi sau MFA hiện lại hai ô.
- Báo cáo bổ sung này; thêm ghi chú phiên bản ở `auth-error-focus-report.md` và bằng chứng trong `login-generic-error-evidence/`. Không xóa file, không migration, không đổi contract/schema/event.

## 5. Security and Business Rules Preserved

Backend vẫn quyết định thông tin đăng nhập và MFA. UI không kết luận tên đăng nhập hay mật khẩu riêng lẻ sai; chỉ có một thông báo chung cho cả cặp. Hai ô cùng viền lỗi, con trỏ thực ở username vì trình duyệt chỉ focus được một phần tử. Không tự gửi OTP/reset, không xóa nội dung hai ô. Lỗi local thiếu dữ liệu vẫn chỉ đúng ô trống. CSRF/payload/OTP/recovery code và quyền nghiệp vụ ngoài auth không đổi. Đây là sửa cách trình bày lỗi, không tuyên bố gia tăng khả năng chống tấn công backend.

## 6. Test Results

Kết quả và log của lượt này lưu ở checkpoint được trỏ bởi `D:/aloute-develop123/aloute-quality-checkpoints/login-generic-error-latest.txt`; không dùng số test lượt trước làm bằng chứng mới.

```powershell
node src/test/browser/auth-error-focus.cjs
mvn -B '-Dtest=AuthTemplateCspTest,AuthControllerWebTest,AuthServiceVerifyEmailTest,PasswordManagementServiceTest,AccountSecurityServiceTest,ModuleBoundaryTest' '-DskipITs' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/login-generic-error-20261025-004623/temp -Duser.timezone=UTC' verify
```

| Suite | Passed | Failed/Error | Skipped |
|---|---:|---:|---:|
| 6 Maven suites nêu trên, bao gồm 12 boundary tests | 38 | 0 | 0 |
| Browser: 4 trang × 2 theme × 2 viewport × 2 motion | 32 | 0 | 0 |

Maven `BUILD SUCCESS` trong 2 phút 12 giây. Browser dùng source thật và HTTP fixtures, không phải SMTP/TOTP thật. Đã xem ảnh login 320px: chỉ một thông báo chung, cả hai ô có viền lỗi, username giữ con trỏ. Sáu tài nguyên runtime trong JAR khớp SHA-256 source. Integration database không chạy lại trong lượt sửa UI này (`-DskipITs`); đây không phải toàn bộ regression backend.

## 7. Performance

Không đo tải/p95; không có tuyên bố cải thiện hiệu năng.

## 8. Remaining Issues và Rollback

Backup 7 file cùng hash: `D:/aloute-develop123/aloute-quality-checkpoints/login-generic-error-20261025-004623/`. Khi rollback phải đối chiếu thay đổi phát sinh, khôi phục đúng phạm vi này rồi build lại; không reset repository hoặc dữ liệu. Chưa deploy/restart ứng dụng hay kiểm chứng thiết bị/screen reader thật. Mục tiêu refactor kiến trúc tổng thể vẫn PARTIAL.

## 9. Final Assessment

**DONE trong phạm vi sửa trình bày lỗi credentials theo yêu cầu**: đã code, chạy 38 Java tests, 32 cấu hình browser và build thành công. Không thay đổi xác minh backend hoặc dữ liệu. Các giới hạn tại mục 8 vẫn chưa kiểm chứng.
