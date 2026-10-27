# Gộp thông báo lỗi trên các trang OTP

Ngày 25/10/2026. Phạm vi: bỏ hộp đỏ phía trên và dòng đỏ lặp dưới OTP theo ảnh người dùng.

## 1. Verified Architecture Snapshot

Đã đọc AGENTS.md và source hiện tại của helper feedback, component OTP, ba consumer, templates và browser tests. Spring Boot 4.1.1, Java target 21; lượt kiểm thử chạy JDK 22. Frontend hiện hữu là Thymeleaf + JavaScript thuần. Không có `.git` trong workspace; giữ nguyên tình trạng này theo yêu cầu trước đó. Không đọc cấu hình chứa secrets.

`Confirmed Bug` về UX: cùng một lỗi được hiển thị bởi summary của helper, inline hint của field và status của widget OTP. `Already Correct`: kiểm tra OTP ở backend; khóa form khi đang chờ; chỉ xác nhận thành công sau phản hồi server; mã đúng trước khi mở trang mật khẩu mới. Baseline tham khảo là báo cáo hai bước trước đó; không chạy lại toàn bộ baseline trước sửa trong lượt giao diện này.

## 2. Module Migration Status

| Module | Thay đổi lượt này | Kiểm chứng | Trạng thái |
|---|---|---|---|
| Identity (UI auth) | Một vùng thông báo trung tính ở xác thực email, xác thực reset và MFA đăng nhập | Browser + các suite Java liên quan | Implemented; chi tiết mục 6 |
| Users, Social, Posts, Feed, Communities, Messaging/Calls, Notifications, Media/Moderation, Administration | Không sửa implementation | Không kiểm tra nghiệp vụ đầy đủ lượt này | Không tuyên bố hoàn tất refactor |

## 3. Dependency / Architecture Violations

Helper feedback nhận vùng thông báo của widget qua API, không dùng selector toàn cục để ẩn tất cả lỗi. Chỉ màn hình/bước OTP đăng ký vùng này. Bước nhập username/password giữ summary chung của cặp thông tin đăng nhập. Không thay đổi dependency hay ownership backend. ModuleBoundaryTest hiện hữu: 12 bài đạt.

## 4. Changed Files

Các đường dẫn dưới đây tính từ repository:

- `src/main/resources/static/js/auth-form-feedback.js`: hỗ trợ một vùng thông báo cho field và lỗi chung; chuyển focus đúng vùng; giữ aria-describedby.
- `src/main/resources/static/js/auth-otp.js`: công khai vùng status cho consumer, giữ đúng nội dung backend thay vì ghi đè bằng lỗi chung; giữ rung, xóa mã số sai và focus. Giữ mã khôi phục khi sai.
- `src/main/resources/static/css/auth-otp.css`: status vẫn trung tính khi rung; sáu ô còn đánh dấu lỗi.
- `src/main/resources/static/js/auth-verify.js`, `auth-verify-reset-code.js`, `auth-login.js`: nối feedback vào status OTP; giữ lỗi mạng/rate/timeout, phục hồi summary khi trở về credentials.
- `src/main/resources/templates/auth/login.html`, `verify.html`, `verify-reset-code.html`, `forgot-password.html`, `reset-password.html`: cập nhật version tài nguyên để tránh cache cũ.
- `src/test/browser/auth-error-focus.cjs`: assertions cho summary ẩn và rỗng, hint ẩn và rỗng, một status trung tính chứa đúng lỗi, aria, focus, rate/expiry; giữ assertions hợp đồng, single-flight và OTP.
- Báo cáo này và evidence là file mới. Không xóa file; không migration, đổi schema, endpoint, request/response, event hay Java implementation.

## 5. Security and Business Rules Preserved

Không nuốt lỗi: thông báo mã sai/hết hạn, giới hạn thử, lỗi mạng và timeout vẫn hiển thị tại status. Sai OTP vẫn đánh dấu aria-invalid, rung, xóa mã số rồi focus; mã khôi phục không bị xóa. Lỗi mạng/rate không bị quy thành sai mã và không xóa mã đang nhập. Không đổi CSRF, OTP validation, proof HttpOnly, expiry hoặc backend gate của reset. Đăng nhập sai thông tin vẫn báo lỗi chung và đánh dấu cả username/password. Feed, privacy, chat/call và media không bị sửa.

## 6. Test Results

| Command / suite | Đạt | Lỗi | Bỏ qua | Ghi chú |
|---|---:|---:|---:|---|
| Maven verify với 8 suite chọn lọc | 46 | 0 | 0 unit | BUILD SUCCESS; IT chủ động bỏ qua |
| `node src/test/browser/auth-error-focus.cjs` | 32 cấu hình | 0 | 0 | Focus, retry, lỗi trùng, màu trung tính, expiry/rate, hợp đồng, CSRF |
| `node src/test/browser/auth-error-focus.cjs otp` | 24 cấu hình | 0 | 0 | Paste, keyboard, shake/wave, recovery, leading-zero, server quyết định thành công |
| `node src/test/browser/auth-error-focus.cjs reliability` | 4 ca | 0 | 0 | Timeout, network, late response, single-flight |
| `node src/test/browser/password-reset-flow.cjs` | 8 cấu hình | 0 | 0 | Luồng OTP trước mật khẩu, proof hết hạn và retry |

Tổng 68 cấu hình/ca browser đều đạt. Maven chạy thực tế:

```powershell
$checkpoint=(Get-Content 'D:/aloute-develop123/aloute-quality-checkpoints/otp-single-message-latest.txt' -Raw).Trim()
$env:TEMP="$checkpoint/temp"; $env:TMP="$checkpoint/temp"
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2'
mvn -B '-Dtest=AuthTemplateCspTest,GlobalThemeTemplateTest,AuthControllerWebTest,PasswordResetControllerTest,PasswordResetFlowServiceTest,PasswordManagementServiceTest,AccountSecurityServiceTest,ModuleBoundaryTest' '-DskipITs' "-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=$checkpoint/temp -Duser.timezone=UTC" verify
```

Browser sử dụng `ALOUTE_BROWSER_EXECUTABLE=C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe`, `ALOUTE_PLAYWRIGHT_MODULE=C:/Users/buiph/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright`, `ALOUTE_AUTH_EVIDENCE=$checkpoint/browser`.

Evidence: `docs/quality/otp-single-message-evidence/` chứa logs, JSON và ảnh đại diện.

Browser dùng Edge headless, source template/JS/CSS thật với HTTP fixtures cho auth; không phải kiểm thử tích hợp production/backend thật. Matrix giao diện: dark/light, 1280/320 px, motion thường/reduce. Deadline test rút riêng 30 giây xuống 180 ms. Không chạy PostgreSQL IT hay toàn bộ suite backend trong lượt UI này; Maven dùng `-DskipITs`.

Backup 12 file gốc đã xác minh SHA256. 11 tài nguyên runtime trong JAR đã đối chiếu byte với source; `app.js` giữ nguyên checksum. Không restart/deploy ứng dụng đang chạy.

## 7. Performance

Không đo latency, throughput, query count hay p95; NOT VERIFIED. Không thay đổi query/index/cache backend.

## 8. Remaining Issues

- Chưa kiểm chứng với mail thật, tài khoản thật hoặc ứng dụng production; browser dùng fixtures.
- Không có `.git`, nên không thể phân biệt diff toàn workspace bằng Git; chỉ liệt kê và kiểm tra các file được backup trước sửa.
- Nếu server hiện tại đang giữ bản JAR/template cũ, cần reload bằng bản build mới qua quy trình khởi động hiện hữu; lượt này không tự restart tiến trình của người dùng.

## 9. Final Assessment

DONE trong phạm vi thay đổi giao diện OTP: Implemented và Tested với 46 bài Java chọn lọc + 68 cấu hình/ca browser. Chưa kiểm thử tích hợp PostgreSQL hoặc production trong lượt này.

Backup/rollback: dừng hoặc đưa ứng dụng về quy trình cập nhật an toàn hiện hữu, khôi phục toàn bộ 11 file runtime trong checkpoint (JS/CSS/templates cùng phiên bản), build lại rồi reload. Không phục hồi từng file helper riêng lẻ vì các consumer dùng API mới. Không có bước database hay migration. Bản backup có `before.json`; evidence có logs, ảnh và JSON kết quả. Kiến trúc Modular Monolith tổng thể vẫn PARTIAL ngoài phạm vi yêu cầu giao diện này.

Checkpoint đầy đủ: `D:/aloute-develop123/aloute-quality-checkpoints/otp-single-message-20261025-015749`.
