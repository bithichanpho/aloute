# OTP vừa chuyển trang rồi trở về đăng nhập: backend đang chạy chưa nạp route mới

Ngày 25/10/2026. Phạm vi: truy nguyên hiện tượng gửi mã quên mật khẩu rồi lập tức về login trên backend local thật.

## 1. Verified Architecture Snapshot

Runtime local tại `http://localhost:8081` là Java PID 2352, được SpringToolsForEclipse khởi động từ 19:40:38 ngày 24/10/2026. Source/JAR hiện tại đã có PasswordResetController và public matcher `/verify-reset-code`; Java target 21, Spring Boot 4.1.1. JAR được build sau thời điểm khởi động JVM. Workspace không có `.git`, không init/reset.

`Confirmed Bug` tại runtime: GET `/verify-reset-code` chưa đăng nhập trả HTTP 302 đến `/login`; trong khi `/reset-password` không có proof lại trả trang mật khẩu HTTP 200. Source hiện tại mong đợi ngược lại: trang OTP public HTTP 200, trang mật khẩu cần proof và chuyển về OTP nếu chưa có.

`Confirmed Runtime Mismatch`: JS quên mật khẩu được server phục vụ đã là bản điều hướng ngay tới route mới; Spring context đang chạy vẫn hành xử như trước khi có route/gate này. Cập nhật static/template hoặc F5 không đăng ký lại controller bean và SecurityFilterChain. Các kết quả read-only xác nhận đúng chuỗi chuyển trang người dùng báo. Không quy lỗi cho mã OTP hoặc xóa kiểm tra đăng nhập để né hiện tượng.

`Already Correct`: source và JAR mới có controller, public matcher và proof gate. Kiểm tra trực tiếp JAR đã xác nhận các mục này và script khớp source.

## 2. Module Migration Status

| Module | Hiện trạng | Thay đổi lượt này | Trạng thái |
|---|---|---|---|
| Identity | Source mới, runtime Spring context cũ | Thêm smoke test trực tiếp backend local, xác định cần restart context | Chờ reload runtime |
| Users và các module nghiệp vụ khác | Ngoài phạm vi | Không đổi implementation/persistence | Không tuyên bố audit/refactor lại |

## 3. Dependency / Architecture Violations

Không thêm dependency hoặc nới lỏng authorization. Spring security hiện hữu đúng trong source; không đổi permitAll toàn ứng dụng, không bỏ proof hoặc kiểm tra OTP. Sai lệch nằm ở tiến trình đang chạy chưa nạp source mới.

## 4. Changed Files

- New: `src/test/browser/password-reset-route-smoke.cjs`, gọi GET trực tiếp backend local, không mock, không theo redirect ngầm, không POST/cookie đăng nhập/mail. Kiểm tra trang quên mật khẩu, trang OTP, proof gate và bảo vệ Feed. Chỉ cho URL HTTP loopback và không credential.
- New: báo cáo này và `docs/quality/otp-runtime-redirect-evidence/` (metadata HTTP, trạng thái smoke test, kiểm tra artifact; không lưu HTML/cookies/secrets).
- Không thay source implementation vì hành vi mong đợi đã có và đã build. Không đổi migration, schema, endpoint hoặc dữ liệu. Không ghi đè file hiện hữu; các backup trước còn nguyên.

## 5. Security and Business Rules Preserved

Giữ nguyên yêu cầu OTP đúng mới mở trang mật khẩu, CSRF, HttpOnly proof, expiry và rate limits. Smoke test không gửi OTP/email thật hoặc đổi mật khẩu. Chưa dừng/kill/restart JVM đang chạy từ IDE; người dùng cần Stop và Run lại ứng dụng trong Spring Tools để nạp controller/wiring/filter chain mới.

## 6. Test Results

Lệnh thực tế:

```powershell
$env:ALOUTE_BASE_URL='http://localhost:8081'
$env:ALOUTE_AUTH_EVIDENCE=$checkpoint
node src/test/browser/password-reset-route-smoke.cjs before
```

| Route, anonymous GET | Mong đợi source mới | Runtime trước restart | Kết quả |
|---|---|---|---|
| `/forgot-password` | 200, forgotForm | 200, forgotForm | PASS |
| `/verify-reset-code` | 200, resetCodeForm | 302 → /login | FAIL |
| `/reset-password`, không proof | 302 → /verify-reset-code | 200, resetForm | FAIL |
| `/feed` | 302 → /login | 302 → /login | PASS |

Smoke test thật: 2 PASS, 2 FAIL; không báo toàn bộ PASS. Đối chiếu JAR: controller, route security, route controller, JS hiện tại đều đúng. Các bước build/unit/browser của lượt sửa trước là bằng chứng lịch sử tại `forgot-otp-navigation-report.md`, không báo là đã chạy lại trong lượt chẩn đoán này.

Kiểm tra lại lần hai: vẫn PID 2352, start time 24/10/2026 19:40:38; smoke vẫn 2 PASS / 2 FAIL. Chưa có restart. Các file `runtime-after.*` là lần kiểm tra lại sau chẩn đoán, không phải bằng chứng đã restart hoặc đã sửa runtime.

## 7. Performance

Không đo p95, latency hay throughput. Không có thay đổi query/index hoặc load test.

## 8. Remaining Issues

Cần Stop rồi Run lại ALOUTE trong Spring Tools for Eclipse (nạp ứng dụng Java mới, không chỉ refresh trình duyệt). Khi server sẵn sàng, chạy lại smoke test; kỳ vọng 4/4 PASS trước khi thử gửi mã thật. Không đọc/copy run configuration hoặc secrets. Không tự deploy hoặc chạy migration production.

## 9. Final Assessment

PARTIALLY COMPLETE: xác định và tái hiện đúng nguyên nhân bằng backend thật; source/JAR mới đã sẵn sàng. Runtime chưa được restart trong IDE, nên chưa tuyên bố lỗi đã hết. Đã hướng dẫn Stop/Run lại và gửi câu hỏi trạng thái; cần kiểm tra lại 4 route khi backend khởi động xong.

Không có thay đổi runtime cần rollback trong lượt này. Test chỉ đọc localhost và lưu metadata không nhạy cảm. Các file source mới có thể giữ lại làm gate kiểm tra sau mỗi lần khởi động backend.

Checkpoint: `D:/aloute-develop123/aloute-quality-checkpoints/otp-runtime-redirect-20261025-030023`.
