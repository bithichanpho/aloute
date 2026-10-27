# Báo lỗi và focus ô nhập sai trong các luồng xác thực

Ngày kiểm tra: 25/10/2026. Phạm vi theo xác nhận của người dùng: **báo lỗi và focus ô sai trên trang hiện tại**.

**Cập nhật sau phản hồi giao diện:** hành vi chỉ đánh dấu mật khẩu của bản `focus-v1` bên dưới đã được thay bằng lỗi chung và đánh dấu cả username/password. Xem [báo cáo bổ sung](login-generic-error-report.md). Kết quả dưới đây ghi nhận phiên bản trước thay đổi bổ sung đó.

## 1. Verified Architecture Snapshot

Repository hiện tại dùng Spring Boot 4.1.1, target Java 21; lần chạy này dùng JDK 22, Maven 3.9.11. PostgreSQL JPA/JSONB, Redis và STOMP/WebRTC có sẵn; không thêm GraphQL, database hay framework. Các trang auth là Thymeleaf và JavaScript độc lập, gọi `Aloute.api` có CSRF.

`Confirmed Bug`: bốn trang login/verify/forgot-password/reset-password chỉ có thông báo chung, không nhất quán về focus, nhãn trường và lỗi inline. Chạy lại bản backup trên trình duyệt: 4/4 trang không đánh dấu `aria-invalid`; sau lỗi focus ở body/nút gửi thay vì trường sai. `Already Correct`: backend quyết định mật khẩu, MFA, email OTP, reset OTP; thông báo trung tính chống dò tài khoản và các API hiện tại được giữ. Không sửa lại backend cho yêu cầu UI này.

Không có `.git` trong workspace; đã kiểm tra trước thay đổi và không khởi tạo/reset Git. Các sửa đổi từ các nhiệm vụ trước được giữ lại. Backup trước sửa gồm 8 file và SHA-256 ở `D:/aloute-develop123/aloute-quality-checkpoints/auth-error-focus-20261025-002327/`; cả 8 bản sao đã đối chiếu hash.

## 2. Module Migration Status

| Module | Thay đổi trong lượt này | Boundary Test | Functional/Integration Test | Trạng thái |
|---|---|---|---|---|
| Identity | Helper phản hồi lỗi và 4 adapter trang auth | 12 architecture tests hiện hữu đạt | Browser + unit auth + Spring/PostgreSQL | Implemented, Tested trong phạm vi này |
| Users, Social, Posts, Feed | Không đổi ownership/API | Có trong bộ unit hiện hữu | FullBackendContextIT có Feed visibility và thứ tự ngày giờ | Giữ nguyên; không tuyên bố audit mới toàn bộ |
| Communities, Messaging/Calls, Notifications | Không đổi | Bộ boundary hiện hữu đạt | Unit hiện hữu; không chạy lại IT chuyên biệt trong lượt này | Không mở rộng phạm vi |
| Media/Moderation, Administration | Không đổi | Bộ boundary hiện hữu đạt | Unit hiện hữu | Không mở rộng phạm vi |

## 3. Dependency / Architecture Violations

Không phát sinh dependency Java hoặc truy cập repository chéo module. Helper chỉ thuộc UI auth, có 4 consumer thật; tải sau `app.js`, trước script trang. CSS giới hạn trong `.auth-error-focus`; không đổi CSS toàn cục hay `app.js`.

## 4. Changed Files

Mới:

- `src/main/resources/static/js/auth-form-feedback.js`: nhãn, lỗi inline, summary có liên kết bàn phím, `aria-invalid`/`aria-describedby`, cuộn và focus/select; validator email/password mới; gửi yêu cầu có deadline 30 giây và readonly khi chờ.
- `src/main/resources/static/css/auth-form-feedback.css`: trường sai, focus, hint, chuyển động có hỗ trợ reduced motion; giữ attribution của prompt #244.
- `src/test/browser/auth-error-focus.cjs`: tái hiện trước sửa, 32 cấu hình hành vi, 4 ca timeout/network/single-flight.
- Báo cáo này và `docs/quality/auth-error-focus-evidence/`.

Sửa:

- `src/main/resources/static/js/auth-login.js`: sai thông tin đăng nhập -> hiện bước credentials, focus/select mật khẩu; challenge 2FA thực từ server -> bước mã; sai mã -> focus/select mã. Khi trở về credentials, xóa mã cũ. Mã khôi phục không bị ép thành 6 số. Không áp chính sách mật khẩu mới vào đăng nhập tài khoản cũ.
- `src/main/resources/static/js/auth-verify.js`: OTP đăng ký sai/hết hạn -> code; email không hợp lệ -> email; giữ resend cooldown dù verify thất bại.
- `src/main/resources/static/js/auth-forgot-password.js`: email không hợp lệ -> email; chỉ chuyển reset sau ACK; không gửi trùng khi đang chờ/chuyển trang.
- `src/main/resources/static/js/auth-reset-password.js`: OTP sai/hết hạn -> code; mật khẩu mới/yêu cầu xác nhận sai -> đúng ô tương ứng; Unicode và giới hạn 72 byte UTF-8 theo chính sách backend hiện có.
- `src/main/resources/templates/auth/{login,verify,forgot-password,reset-password}.html`: scope CSS, thêm helper và cache version `20261025-focus-v1`. Giữ form IDs/names, URL, các field, autocomplete và scripts tích hợp hiện hữu.

Không xóa file, không có migration mới, không thay REST/schema/event/payload. `register.html`, `auth-register.js` và `app.js` giữ nguyên trong lượt này. SHA-256 của 10 tài nguyên runtime trong JAR khớp source sau build.

## 5. Security and Business Rules Preserved

Client chỉ hỗ trợ nhập liệu; backend vẫn xác thực mật khẩu/OTP, thời hạn, single-use, MFA và rate limit. Sai mật khẩu không kích hoạt gửi OTP/reset tự động. OTP email và reset vẫn gồm 6 chữ số; mã khôi phục MFA giữ nguyên chuỗi gửi lên server. Không thay đổi JSON bodies hay HTTP status.

Lỗi mật khẩu/mã được ánh xạ từ thông báo backend hiện hữu; lỗi mạng/timeout/rate limit tập trung vào khối thông báo chung, không đánh dấu nhầm mật khẩu/OTP. Không đưa chuỗi lỗi vào `innerHTML`. CSRF header được kiểm tra trong browser fixtures; CSRF/logout/replay/session isolation được chạy lại qua Spring Security/PostgreSQL. Các quy tắc block/mute/private/group, Chat ownership, notification privacy, call state, media retention không đổi mã trong lượt này.

## 6. Test Results

| Lệnh/Test suite thực chạy | Passed | Failed/Error | Skipped | Ghi chú |
|---|---:|---:|---:|---|
| Baseline auth: AuthTemplateCspTest, AuthControllerWebTest, AuthServiceVerifyEmailTest, PasswordManagementServiceTest, AccountSecurityServiceTest | 26 | 0 | 0 | Trước thay đổi |
| Maven verify: toàn bộ unit tests | 409 | 0 | 1 | Bao gồm 12 ModuleBoundaryTest; AlouteApplicationTests thiếu điều kiện DB_URL |
| Maven verify: FullBackendContextIT + RegistrationBusinessIT | 4 | 0 | 0 | Spring wiring + PostgreSQL 18.3 + Redis riêng cho test |
| Browser `auth-error-focus.cjs` | 32 | 0 | 0 | 4 trang × 2 theme × 2 viewport × 2 motion |
| Browser `auth-error-focus.cjs reliability` | 4 | 0 | 0 | Timeout/mất mạng, phản hồi muộn, khóa input, single-flight, retry |
| Browser `register-validation.cjs` | 8 | 0 | 0 | Regression form đăng ký hiện hữu |

Lệnh Maven sau thay đổi:

```powershell
mvn -B '-Dit.test=FullBackendContextIT,RegistrationBusinessIT' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/auth-error-focus-20261025-002327/temp -Duser.timezone=UTC' verify
node src/test/browser/auth-error-focus.cjs before
node src/test/browser/auth-error-focus.cjs
node src/test/browser/auth-error-focus.cjs reliability
node src/test/browser/register-validation.cjs
```

Maven `BUILD SUCCESS`, thời gian 3 phút 37 giây. Đây là toàn bộ unit và **2 IT suites được chọn**, không phải toàn bộ integration suites. Không dùng kết quả lượt trước để tính vào số đạt lượt này. Browser chạy Edge headless trên template/script/CSS thật với HTTP fixtures; không phải browser end-to-end nối SMTP/backend thật. Ca deadline rút 30 giây xuống 180ms trong môi trường test, không thay deadline production. Kiểm tra focus còn trong viewport, không tràn ngang 320px, selection, liên kết bằng Enter, aria description, retry, invalid/expired OTP, wrong credentials, MFA recovery code, resend cooldown, payload/CSRF và chuyển trang sau ACK. Đã xem ảnh sáng/tối trên màn hình nhỏ.

IT chỉ dùng loopback database `aloute_quality_datetime_20261024` và Redis 56379; fixtures nghiệp vụ được rollback, mail dùng test double. Sau test đã dừng đúng PostgreSQL/Redis do tác vụ sở hữu, giữ file database test. Không chạm database production hay instance ứng dụng đang chạy của người dùng.

## 7. Performance và Rollback

Không đo p95/throughput/load; **NOT VERIFIED**, không tuyên bố tăng hiệu năng. Không migration trong lượt này.

Rollback UI: khôi phục đúng 8 file từ backup sau khi đối chiếu các thay đổi phát sinh về sau; bỏ 2 tài nguyên helper nếu không còn consumer. Không khôi phục cả repository hoặc ghi đè thay đổi khác. Build lại artifact bằng quy trình hiện có. Không rollback dữ liệu, không cần đổi backend API. Chưa tự deploy/restart ứng dụng.

## 8. Remaining Issues

- SMTP, Google/Firebase thật, TOTP bằng thiết bị thật, screen reader/mobile thật và BFCache thực: **NOT VERIFIED** trong lượt này. Có handler khôi phục form khi `pageshow.persisted`, chưa coi đó là bằng chứng BFCache thật hoạt động trên mọi thiết bị.
- UI đang nhận diện một số lỗi xác thực từ thông báo tiếng Việt hiện hữu vì API chưa có mã lỗi riêng ổn định. Khi thay thông báo backend cần cập nhật regression tương ứng; lỗi lạ vẫn được hiển thị an toàn ở summary.
- Các IT chuyên biệt Calls/Media/Outbox, performance opt-in và load nhiều instance không chạy lại trong lượt sửa UI này.
- Toàn bộ mục tiêu refactor kiến trúc vẫn **PARTIAL** theo các báo cáo kiến trúc; thay đổi này không đủ bằng chứng để nghiệm thu toàn bộ backend.

## 9. Final Assessment

**Implemented + Tested cho báo lỗi/focus bốn trang auth**; production/provider và các giới hạn tại mục 8 còn chưa kiểm chứng. Yêu cầu sai mật khẩu/OTP vẫn ở trang hiện tại đã có implementation và regression tests, giữ các bước xác minh do server quyết định. Bằng chứng trong thư mục kèm báo cáo; log đầy đủ và bản khôi phục nằm trong checkpoint ngoài repository.
