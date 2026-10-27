# Quên mật khẩu: chuyển sang nhập OTP sau khi gửi yêu cầu

Ngày 25/10/2026. Người dùng xác nhận lỗi tại trang Quên mật khẩu; lượt này không sửa nút resend ở đăng nhập hoặc đăng ký.

## 1. Verified Architecture Snapshot

Đã đọc AGENTS.md và audit JS/template quên mật khẩu, helper API, controller, password reset service, SecurityConfig, NoCacheViewConfig và tests hiện hữu. Spring Boot 4.1.1, Java target 21, chạy kiểm thử JDK 22; Thymeleaf + JavaScript thuần. Persistence PostgreSQL/JPA, Redis và backend OTP giữ nguyên. Workspace không có `.git`; không init/reset. Không đọc/in secrets hoặc gọi mail thật.

- `Already Correct`: code trước sửa đã chuyển `/forgot-password` → `/verify-reset-code?email=...` khi API thành công, kể cả `data:null`. Baseline normal và rate/network/retry đạt. Route nhập mã đã tồn tại và có public/no-cache matcher.
- `Confirmed Bug` về độ tin cậy: điều hướng phụ thuộc timer 1.500 ms; thử với JS timers bị tạm dừng không chuyển trang sau ACK. Khi document được giữ lại sau yêu cầu điều hướng (fixture HTTP 204), không có liên kết để tiếp tục.
- `Requires Integration Testing`: chưa có trace từ phiên thực tế người dùng; không kết luận tab của người dùng đã bị tạm dừng hoặc mail/backend lỗi. Khi audit không tìm thấy listener trên các cổng local 8080/8081/8082. Việc này không chứng minh ứng dụng không chạy ở cổng/máy khác.

## 2. Module Migration Status

| Module | Trước sửa | Thay đổi | Kiểm chứng | Trạng thái |
|---|---|---|---|---|
| Identity, UI quên mật khẩu | Chờ timer, thiếu đường tiếp tục nếu document còn lại | Điều hướng ngay sau ACK; liên kết nhập mã dự phòng; trạng thái đã gửi rõ ràng | Browser + Java chọn lọc | Implemented; kết quả mục 6 |
| Users | Sở hữu dữ liệu OTP/password hiện hữu | Không đổi | Unit service/controller hiện hữu | Không migration |
| Social, Posts/Feed, Communities, Messaging/Calls, Notifications, Media/Moderation, Administration | Ngoài phạm vi lượt này | Không đổi | Không chạy kiểm thử nghiệp vụ đầy đủ | Không tuyên bố hoàn tất refactor |

## 3. Dependency / Architecture Violations

Không thêm dependency, port, package hoặc infrastructure. UI gọi nguyên API `/api/auth/forgot-password`; mã OTP vẫn phải được backend kiểm tra tại bước kế tiếp. ModuleBoundaryTest hiện hữu được chạy trong Maven gate. Không thay owner ghi dữ liệu, transaction hoặc public API.

## 4. Changed Files

- Modified: `src/main/resources/static/js/auth-forgot-password.js`: bỏ timer điều hướng; sau API ACK ghi URL bằng email đã trim/encode, hiện liên kết dự phòng, chuyển ngay tới trang mã. Nếu navigation ném lỗi thì hướng dẫn và focus liên kết; không báo lại là gửi OTP thất bại. Nút chuyển thành Đã gửi yêu cầu, không còn aria-busy. Khôi phục form khi pageshow persisted như trước.
- Modified: `src/main/resources/templates/auth/forgot-password.html`: thêm span thông báo và liên kết Nhập mã OTP trong status; link nằm dưới nội dung, không chồng chữ trên màn hình nhỏ; tăng version script tránh cache cũ.
- New: `src/test/browser/forgot-otp-navigation.cjs`: baseline và regression cho normal ACK, timers suspended, document retained, rate/network/retry, request single-flight, CSRF, payload, email chứa dấu +, dark/light, 320/1280 và reduced motion. Có kiểm tra bố cục fallback.
- New: báo cáo này, logs/JSON/ảnh trong `docs/quality/forgot-otp-navigation-evidence/`.
- Không xóa file; không đổi Java, CSS chung, app.js, schema, migration, endpoint, JSON contract hay event.

## 5. Security and Business Rules Preserved

Chỉ chuyển khi API gửi yêu cầu ACK thành công; không chuyển sớm trong khi chờ, không chuyển khi mạng/rate lỗi. Giữ thông báo trung tính, không xác nhận email tồn tại hoặc mail chắc chắn tới hộp thư. Payload vẫn chỉ có email, giữ CSRF và deadline. Email trong URL được encode; không có password/OTP/proof trong URL hoặc browser storage. Không bỏ bước xác thực: trang mật khẩu vẫn cần proof từ mã hợp lệ. Không sửa privacy, feed ranking, chat/call hay dữ liệu.

## 6. Test Results

| Command / suite | Đạt | Lỗi | Bỏ qua | Ghi chú |
|---|---:|---:|---:|---|
| `node src/test/browser/forgot-otp-navigation.cjs before` trước sửa | 2 | 2 | 0 | Baseline 4 ca: normal/rejected đạt; suspended-timers và thiếu fallback thất bại như đã mô tả |
| `node src/test/browser/forgot-otp-navigation.cjs` bản cuối | 32 | 0 | 0 | 8 cấu hình × 4 tình huống; có layout và trạng thái hoàn tất |
| `node src/test/browser/auth-error-focus.cjs reliability` chạy riêng bản cuối | 4 | 0 | 0 | Timeout/network/late response/single-flight |
| `node src/test/browser/password-reset-flow.cjs` bản cuối | 8 | 0 | 0 | OTP trước trang mật khẩu, expiry, retry, password policy |
| Maven verify 8 suite chọn lọc bản cuối | 46 | 0 | 0 unit | BUILD SUCCESS; IT bỏ qua bằng `-DskipITs` |

Tổng lượt cuối: 44 cấu hình/ca browser và 46 bài Java đạt. Không cộng dồn các lượt chạy lặp.

Có một lượt reliability chạy đồng thời Maven/browser thất bại tại `auth-error-focus.cjs:150`, kiểm tra readonly trước deadline đã rút xuống 180 ms. Chạy riêng nguyên test không chỉnh sửa thì cả 4 ca đạt. Đây là bằng chứng test nhạy timing khi máy tải cao, không đủ để kết luận nguyên nhân tuyệt đối; giữ cả log thất bại `browser-reliability-final.log` và log chạy lại `browser-reliability-isolated.log`. Không đổi test để bỏ qua lỗi.

Đã kiểm tra checksum 2 file backup và đối chiếu byte 2 tài nguyên runtime trong JAR với source cuối. `app.js` giữ nguyên checksum. Evidence tại `docs/quality/forgot-otp-navigation-evidence/`.

Browser dùng template/JS/CSS hiện tại và HTTP fixtures, không dùng backend/mail/DB thật. Ca suspended-timers dùng Playwright clock; ca retained-document dùng HTTP 204 cho lần điều hướng đầu. Không khẳng định tái hiện chính xác điều kiện trên trình duyệt người dùng. Ảnh của bản sửa đầu phát hiện link chồng chữ; đã chỉnh layout và chạy lại gate trên bản cuối, không bỏ assertion.

Maven thực tế (TEMP/TMP đặt vào checkpoint, MAVEN_OPTS giới hạn heap/metaspace/processor như lượt trước):

```powershell
mvn -B '-Dtest=AuthTemplateCspTest,GlobalThemeTemplateTest,AuthControllerWebTest,PasswordResetControllerTest,PasswordResetFlowServiceTest,PasswordManagementServiceTest,AccountSecurityServiceTest,ModuleBoundaryTest' '-DskipITs' "-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=$checkpoint/temp -Duser.timezone=UTC" verify
```

Browser sử dụng Edge headless, `ALOUTE_BROWSER_EXECUTABLE=C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe`, `ALOUTE_PLAYWRIGHT_MODULE=C:/Users/buiph/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright`, `ALOUTE_AUTH_EVIDENCE=$checkpoint/browser`.

NOT RUN: PostgreSQL integration, mail delivery thật, phiên người dùng và production. Không sửa backend/database trong lượt này; IT được bỏ qua có chủ đích, không báo PASS.

## 7. Performance

Đã bỏ hẹn giờ 1.500 ms trong code. Không đo latency, p95, throughput hoặc tốc độ mail; NOT VERIFIED, không tuyên bố cải thiện bằng số đo.

## 8. Remaining Issues

Chưa xác định nguyên nhân trực tiếp trên phiên thực tế người dùng nếu API không trả ACK hoặc ứng dụng dùng bản build cũ. Nếu còn lỗi, cần URL hiện tại và response/status của request gửi mã để phân biệt với lỗi điều hướng. Không restart tiến trình, deploy, thay cấu hình hay migration production trong lượt này.

## 9. Final Assessment

Implemented và Tested trong phạm vi sửa UI quên mật khẩu: điều hướng ngay sau ACK, fallback và trạng thái hoàn tất đã được kiểm chứng. PARTIALLY COMPLETE đối với chẩn đoán sự cố trên phiên thực tế người dùng: chưa có trace/live test, chưa xác nhận ứng dụng của người dùng đã tải bản build mới.

Rollback: khôi phục cả JS và template từ checkpoint, build lại và reload theo quy trình hiện hữu. Giữ hai file cùng phiên bản. Không cần bước database; không có dữ liệu bị thay đổi bởi kiểm thử fixture. Kiến trúc tổng thể ngoài phạm vi vẫn PARTIAL.

Checkpoint: `D:/aloute-develop123/aloute-quality-checkpoints/forgot-otp-navigation-20261025-022221`. `before.json` chứa SHA256 hai file gốc.
