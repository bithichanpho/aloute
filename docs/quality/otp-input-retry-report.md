# OTP: sửa nhập chữ, giữ vị trí sửa mã và kiểm tra nhập lại

Ngày 25/10/2026. Phạm vi: ô OTP sáu chữ số ở xác thực email đăng ký và xác thực mã quên mật khẩu.

## 1. Verified Architecture Snapshot

Đã đối chiếu source hiện tại: component OTP, helper feedback, consumer đăng ký/reset/MFA, token/hash/verification service, rate limiter, controller validation, repository CAS và tests. Spring Boot 4.1.1, Java target 21, lượt kiểm thử chạy JDK 22; Thymeleaf/JS thuần, PostgreSQL/JPA + Redis giữ nguyên. Workspace không có `.git`, không init/reset. Không đọc secrets hoặc tài khoản người dùng.

`Confirmed Bug`: consumer xóa ký tự không phải số sau input bằng gán lại value, làm con trỏ nhảy xuống cuối. Baseline browser thực tế: đang sửa `0145` ở vị trí 2, gõ `a` → số vẫn `0145` nhưng caret thành `[4,4]`, mong đợi `[2,2]`; cả đăng ký và reset cùng lỗi. Theo audit, chữ có thể thay thế/xóa các số đang chọn trước khi bị lọc; paste toàn chữ cũng thay thế phần đang chọn bằng chuỗi rỗng.

`Already Correct`: backend trả lỗi trước khi ghi/tiêu thụ mã khi OTP sai hoặc sai định dạng; hash/expiry của mã đúng không bị đổi chỉ vì lần nhập sai. Có rate limit chống brute force; không gỡ giới hạn này. `Requires Integration Testing`: không có mã OTP/email thật hay trace của tài khoản người dùng, nên không kết luận mọi trường hợp sáu số bị từ chối đều do lỗi con trỏ; mã hết hạn, mã đã thay thế và khóa tạm vẫn cần được phân biệt theo response thực tế.

## 2. Module Migration Status

| Module | Thay đổi | Bằng chứng | Trạng thái |
|---|---|---|---|
| Identity UI OTP | Chặn chữ trước khi thay số; giữ selection/caret; paste toàn chữ không xóa mã; fallback normalize trước feedback | Browser fixture và kiểm tra live chỉ đọc | Implemented, Tested |
| Identity verification/password | Không đổi implementation; thêm kiểm thử sai → đúng không làm hỏng hash/expiry | Unit + PostgreSQL hiện hữu | Kết quả mục 6 |
| Users | Giữ write owner và CAS hiện hữu | Không đổi schema/repository | Unchanged |
| Social, Posts/Feed, Communities, Messaging/Calls, Notifications, Media/Moderation, Administration | Không refactor lượt này | Không tuyên bố kiểm thử đầy đủ | Ngoài phạm vi |

## 3. Dependency / Architecture Violations

Sửa trong component OTP hiện hữu; không thêm framework, dependency hay module. Chỉ nhánh numeric OTP áp dụng lọc mới; login MFA có mã khôi phục giữ cách nhập và API cũ. Public REST, transactions, rate limiter và ownership backend giữ nguyên. ModuleBoundaryTest 12 bài được chạy cùng gate Java.

## 4. Changed Files

- Modified `src/main/resources/static/js/auth-otp.js`: beforeinput từ chối chữ trước khi thay phần số/selection; input capture chuẩn hóa trước helper/legacy sanitizer và tính lại caret theo số ký tự số đứng trước; paste không có số chỉ hướng dẫn, không thay mã đang chọn. Không auto-submit.
- Modified `src/main/resources/templates/auth/verify.html`, `verify-reset-code.html`, `login.html`: version script OTP thành `20261025-input-retry-v3` để tránh dùng cache cũ.
- Modified `src/test/java/com/aloute/service/AuthServiceVerifyEmailTest.java`, `PasswordManagementServiceTest.java`: mã sai/sai định dạng không đổi hash/expiry, sau đó mã đúng vẫn dùng được; không có write/consume ngoài ý muốn.
- New `src/test/browser/otp-input-retry.cjs`: chữ giữa mã, thay vùng chọn, paste toàn chữ, input không có beforeinput, malformed không POST, sai server → gõ chữ → mã đúng, lỗi cũ được xóa, payload/CSRF/leading zero. Matrix dark/light × 1280/320 × motion thường/reduce × hai trang.
- New báo cáo này và `docs/quality/otp-input-retry-evidence/`.
- Không xóa file, không migration, không sửa REST/schema/event/Java business implementation. Không đổi CSS/khung UI hoặc Feed/Chat/Call.

## 5. Security and Business Rules Preserved

Chữ không tự gửi API và không tiêu tốn lượt thử server. Sáu số chỉ gửi khi người dùng xác nhận; backend vẫn quyết định mã hợp lệ và expiry/consume. Sai thật vẫn rung, xóa mã số và focus lại; nhập lại số xóa trạng thái lỗi cũ. Không clear rate limit khi sửa ô, không vô hiệu hóa khóa tạm, không chấp nhận mọi mã. Mã khôi phục MFA vẫn nhận chuỗi như trước. Không ghi token/mật khẩu/cookie vào báo cáo hoặc storage. Không gửi mail hay sửa tài khoản thật.

## 6. Test Results

| Command / suite | Đạt | Fail/Error | Skip | Phạm vi |
|---|---:|---:|---:|---|
| Browser baseline `otp-input-retry.cjs before` | 0 | 2 | 0 | Hai trang đều lệch caret; baseline có chủ đích để tái hiện trước sửa |
| Browser input/retry bản cuối | 16 cấu hình | 0 | 0 | Gõ/dán chữ, selection/caret, fallback input, sai → đúng, không POST ngoài ý muốn |
| Browser OTP regression hiện hữu | 24 cấu hình | 0 | 0 | Login MFA/recovery, đăng ký, reset; keyboard/paste, wave/shake, single-flight |
| Browser trực tiếp local thật, chỉ đọc | 2 trang | 0 | 0 | `/verify-reset-code`, `/verify-email`: số/caret không bị mất, không request ghi |
| Maven unit chọn lọc | 53 | 0 | 0 | Gồm 12 boundary tests, 2 regression mới và token/auth/password suites |
| PostgreSQL `PasswordResetFlowIT` | 6 | 0 | 0 | CSRF, sai → đúng qua HTTP, cookie/proof, expiry/replay, issuance, concurrency |

`mvn ... verify`: BUILD SUCCESS, 3 phút 33 giây. Đây là 10 unit suites và 1 integration suite được chọn, không phải toàn bộ backend tests. Tổng browser fixture 40 cấu hình, cộng 2 kiểm tra live chỉ đọc. Tài nguyên OTP do backend local phục vụ đã đối chiếu byte khớp source mới. Đã xác minh 6 backup SHA256 và 4 tài nguyên runtime trong JAR; app.js giữ nguyên checksum. Đã dừng đúng PostgreSQL/Redis test do tác vụ sở hữu, giữ dữ liệu DB test; không dừng backend người dùng.

Smoke route live hiện tại cũng đạt 4/4; lỗi runtime redirect của lượt trước không còn tái hiện. Metadata trong `runtime-current.json`.

Browser fixture dùng tài nguyên source hiện tại; response success/wrong là test doubles. Kiểm tra live trên `http://localhost:8081` dùng browser riêng không đăng nhập, chỉ GET và nhập dữ liệu giả vào form, chặn mọi request không phải GET/HEAD; không bấm submit. PostgreSQL tests chỉ dùng loopback `aloute_quality_datetime_20261024`, user test, Redis 56379, mail mock và fixtures `reset-it-*` được cleanup đúng owner.

Các lệnh thực tế:

```powershell
node src/test/browser/otp-input-retry.cjs before
node src/test/browser/otp-input-retry.cjs
node src/test/browser/auth-error-focus.cjs otp
mvn -B '-Dtest=AuthTemplateCspTest,GlobalThemeTemplateTest,AuthControllerWebTest,PasswordResetControllerTest,PasswordResetFlowServiceTest,PasswordManagementServiceTest,AuthServiceVerifyEmailTest,AuthTokenServiceTest,AccountSecurityServiceTest,ModuleBoundaryTest' '-Dit.test=PasswordResetFlowIT' "-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=$checkpoint/temp -Duser.timezone=UTC" verify
```

Maven: TEMP/TMP trỏ `$checkpoint/temp`, MAVEN_OPTS `-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2`. IT env dùng URL JDBC loopback port 55432/database test như trên, username aloute_test và credential giả. Browser dùng Edge và Playwright tại runtime Codex hiện hữu, evidence `$checkpoint/browser`.

Lượt browser đầu sau sửa có fixture sai: test nhập chữ lên mã đúng rồi kỳ vọng mã bị thay thành rỗng, trái với yêu cầu mới là giữ nguyên số. Vì vậy test gửi chính mã đúng hiện có. Đã sửa phần chuẩn bị dữ liệu: xóa ô trước khi kiểm tra malformed-only; giữ tất cả assertions bảo toàn số và không POST khi malformed. Log/JSON thất bại vẫn được lưu, không đổi production để làm test pass. Sau đó thêm kiểm tra autofill không có beforeinput và chạy lại đủ matrix.

## 7. Performance

Không có số đo latency/p95/throughput; NOT VERIFIED. Không thay query/index hoặc đo tải lớn.

## 8. Remaining Issues

Không kiểm tra mã SMTP/tài khoản thật. Nếu đủ sáu số vẫn bị backend từ chối, cần message/status thực tế để phân biệt expired/replaced/rate-limit; không bypass các trường hợp đó. Đã hỏi người dùng để phân biệt chữ cái và mã số; sửa ở đây dựa trên lỗi caret được tái hiện ở cả hai trang. Không tự reset counter hay dữ liệu production.

Lỗi runtime cũ ở lượt trước hiện không còn tái hiện: smoke trực tiếp local trong lượt này cho thấy trang OTP 200 và password gate chuyển về OTP; không restart tiến trình IDE trong lượt sửa input.

## 9. Final Assessment

DONE cho lỗi input/caret được tái hiện: Implemented và Tested với unit, PostgreSQL, browser fixture và live chỉ đọc. Chưa xác minh mã email thật của người dùng, mọi lý do bị từ chối mã khác hoặc bộ gõ IME/keyboard điện thoại thực tế.

Rollback: phục hồi component và ba template cùng phiên bản từ backup, build lại rồi reload. Không có bước database, không rollback mã/hash hoặc xóa dữ liệu. Có thể giữ regression tests. Kiến trúc backend tổng thể ngoài phạm vi vẫn PARTIAL.

Checkpoint: `D:/aloute-develop123/aloute-quality-checkpoints/otp-input-retry-20261025-031918`, 6 file gốc với SHA256 trong `before.json`.
