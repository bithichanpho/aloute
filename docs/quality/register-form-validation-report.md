# Đổi form đăng ký theo Lucian UX/UI Prompt #244

## 1. Verified Architecture Snapshot

- Trang `/register` dùng Spring MVC/Thymeleaf, HTML và JavaScript thuần; CSS dùng chung và theme `data-bs-theme="dark|light"`, preference `aloute-theme` do cơ chế sẵn có quản lý.
- Điểm thay thế: `src/main/resources/templates/auth/register.html`, chỉ dành cho đăng ký. Script cũ là `static/js/auth-register.js`; backend nhận bốn trường `fullName`, `username`, `email`, `password` qua `POST /api/auth/register`.
- Form cũ báo một lỗi chung, không có nhãn hiển thị/aria-invalid/tóm tắt lỗi từng ô. Browser baseline: submit trống trả thông báo chung, số trường `aria-invalid=true` bằng 0.
- Dùng đúng bốn trường thật; không thêm Phone/Terms demo, không giả lập hoàn tất đăng ký ở client. Backend/OTP vẫn quyết định kết quả.
- Đã kiểm tra Git: thư mục hiện tại không có `.git`; không init/reset hoặc ghi đè các sửa đổi trước đó.

## 2. Module Migration Status

| Phạm vi | Thay đổi | Trạng thái |
|---|---|---|
| Identity web: register | Component `.u244` trong template hiện có, CSS riêng và script đăng ký hiện có | Implemented + Tested |
| Identity application/API | Giữ nguyên DTO, validation backend, pending registration, OTP và CSRF | Không đổi implementation |
| Module khác và frontend dùng chung | Giữ nguyên | Không refactor trong yêu cầu này |

Không có dependency/module ownership mới. Đây là thay đổi UI được người dùng yêu cầu, không phải tuyên bố hoàn tất refactor toàn backend.

## 3. Dependency / Architecture Violations

Không thêm thư viện, framework, repository access hoặc hạ tầng. CSS mới chỉ có selector bắt đầu trong `.u244` và keyframes `u244-sh`. JavaScript chỉ khởi tạo khi tìm thấy form đăng ký trong `.u244`; không sửa global scripts.

## 4. Changed Files và Backup

- Sửa `src/main/resources/templates/auth/register.html`: nhãn cho bốn ô, bảng tóm tắt lỗi có link, hint riêng, dấu tích, vùng lỗi server và thông báo gửi lại OTP; giữ ID/name/autocomplete/giới hạn nhập/required và liên kết đăng nhập.
- Sửa `src/main/resources/static/js/auth-register.js`: cập nhật trạng thái từng ô sau lần submit đầu, scroll/focus, giữ request body và luồng gửi lại/xác thực email; chặn submit trùng khi request đang chạy.
- Thêm `src/main/resources/static/css/auth-register.css`: ánh xạ `--ux-*` sang token theme hiện có, hình dạng và chuyển động của prompt, hỗ trợ reduced motion. Chỉ template đăng ký tải stylesheet này.
- Thêm `src/test/browser/register-validation.cjs`: browser regression dùng template/CSS/scripts thật với HTTP API fixtures cô lập.
- Không xóa file, không migration, không đổi REST/schema/event. Comment MIT của tác giả giữ ở đầu ba file giao diện.
- Backup trước sửa: **`D:/aloute-develop123/aloute-quality-checkpoints/register-form-20261024-233445/`**. Có README, hai file runtime gốc và `before.json` SHA-256. Các file dùng chung được kiểm tra hash để bảo toàn bản sửa logout trước đó.

Khác biệt có chủ đích với bản demo: giữ bề rộng auth card 410px và giới hạn chiều cao theo viewport, thay vì khung gallery 300×480px; dùng font/theme ALOUTE và bốn trường thật. Không đưa màn hình gallery, dữ liệu mẫu, custom checkbox Terms hoặc Phone vào sản phẩm.

Motion giữ theo prompt: summary `.4s cubic-bezier(.3,1,.4,1)`, hint `.3s` cùng curve, shake `.4s` ±5px, tick `.35s cubic-bezier(.3,1.7,.5,1)`, focus/scroll 520ms cubic ease-out với delay 200ms khi submit sai. Reduced motion bỏ shake/scroll animation và tôn trọng duration tối thiểu `.01ms !important` sẵn có của theme.

## 5. Security and Business Rules Preserved

- API nhận đúng bốn trường; trim họ tên/username/email và giữ nguyên mật khẩu như trước. Không nới quy tắc mật khẩu 8–72 ký tự, chữ hoa/thường/số/ký tự đặc biệt hoặc thay kiểm tra backend.
- Thêm phát hiện email sai định dạng phía client; backend vẫn authoritative về email, tên tài khoản, uniqueness/reserved names, rate limit và OTP.
- Tiếp tục dùng `Aloute.api` với header CSRF; không inline script hoặc nới CSP. Lỗi server và nhãn dùng `textContent`, không chèn HTML từ response.
- Chỉ hiển thị thành công sau server ACK rồi chuyển `/verify-email?email=...`; không auto-submit khi người dùng mới sửa xong và không bỏ bước xác thực email.
- Có label, aria-describedby/aria-invalid, role alert/status, keyboard links, focus indicator. Bảng lỗi ẩn được inert để không giữ link vô hình trong tab order. Lỗi server giữ form values và cho thử lại/gửi lại OTP như cũ.

## 6. Test Results

| Command/Test Suite | Kết quả | Phạm vi |
|---|---|---|
| Baseline Maven, lệnh bên dưới | 25 pass, 0 fail/skip | Trước sửa |
| `node --check src/main/resources/static/js/auth-register.js` | Pass | Cú pháp script |
| `node src/test/browser/register-validation.cjs before` | Pass baseline | Template/script cũ lấy từ backup |
| `node src/test/browser/register-validation.cjs` | 8/8 cấu hình pass | Dark/light × 1280×900 / 320×640 × no-preference/reduce |
| Maven verify, lệnh bên dưới | BUILD SUCCESS, 6 phút 03 giây | 391 unit pass + 1 skip; 57 integration pass + 2 skip; 0 failure/error |

Lệnh Maven đã chạy với các JVM options sau (baseline và sau sửa):

```powershell
mvn -B '-Dtest=AuthTemplateCspTest,AuthServiceRegisterTest,RequestValidationTest,AuthControllerWebTest' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/register-form-20261024-233445/temp -Duser.timezone=UTC' test
mvn -B '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/register-form-20261024-233445/temp -Duser.timezone=UTC' verify
```

Integration dùng database test loopback `aloute_quality_datetime_20261024`, Redis loopback 56379 và browser Edge/Playwright cục bộ, qua biến môi trường test hiện có. Không dùng cấu hình/credentials production. Ba skip: `AlouteApplicationTests` thiếu DB_URL theo điều kiện sẵn có; `DocumentPerformanceIT`, `PerformanceHttpIT` không bật benchmark opt-in cho thay đổi UI. Không tính skip là pass.

Browser tests sử dụng template/styles/scripts từ source với `th:src/href` được chuyển thành URL tĩnh cho fixture; không giả định đây là trình duyệt end-to-end vào production. Kiểm tra keyboard Enter, link lỗi → focus, cuộn ô lỗi vào vùng nhìn thấy, count giảm/tăng lại, hint/tick, không tràn ngang, server error/retry/pending OTP, gửi lại OTP, duplicate submit, body/CSRF và redirect. API/mail được fixture thay thế, không gửi email thật.

Đã xem ảnh desktop dark/light và mobile: card, label, summary/hint, focus ring và nút vẫn đọc/điều hướng được; mobile ngắn cuộn bên trong card như prompt. Evidence JSON, preview và SHA-256 nằm trong `docs/quality/register-form-evidence/`; toàn bộ ảnh/log và bản gốc ở thư mục backup. Test giảm chuyển động ban đầu kỳ vọng duration đúng `0s`; đã chỉnh assertion thành mức không đáng kể ≤0.01ms để kiểm tra đúng quy tắc `!important` sẵn có của theme, giữ assertion không shake và toàn bộ hành vi cuối. Không đổi global CSS để ép test pass.

## 7. Performance

Không đo throughput/latency và không tuyên bố cải thiện hiệu năng backend. Không thêm dependency ngoài, font ngoài hoặc request validation theo từng lần gõ.

## 8. Remaining Issues

- Chưa kiểm chứng mail/OTP delivery với provider thật, production proxy/CSP, thiết bị iOS/Android thật, screen reader hoặc autofill/password manager thật.
- Browser test chạy Edge headless; kiểm tra bằng mắt qua ảnh chụp là bổ sung, không thay thế kiểm chứng trên thiết bị thật.

## 9. Final Assessment và Rollback

**DONE trong phạm vi thay form đăng ký và kiểm thử cục bộ**: mã thật, Spring template/static resource wiring, API compatibility, backup và regression đã kiểm chứng. Provider mail/thiết bị thật/production vẫn NOT VERIFIED như mục 8. Không thay trạng thái PARTIAL của refactor toàn backend từ thay đổi giao diện này.

Rollback: khôi phục đúng hai file runtime gốc trong backup về repository sau khi đối chiếu thay đổi phát sinh; stylesheet mới có thể để lại vì template cũ không tải. Build/nạp lại ứng dụng; không cần rollback dữ liệu. Không tự restart/deploy instance đang chạy của người dùng.
