# Hậu kiểm nghiệp vụ form đăng ký — 25/10/2026

## 1. Verified Architecture Snapshot

Phạm vi là luồng đăng ký vừa thay giao diện, gồm Thymeleaf/JavaScript → AuthController → AuthService → PostgreSQL, validation và OTP. Java target 21, Maven chạy JDK 22, Spring Boot 4.1.1; PostgreSQL test 18.3. Không tuyên bố đã audit xong mọi nghiệp vụ của cả ALOUTE.

Kiểm tra lần trước chủ yếu bao phủ tương tác giao diện với fixture, chưa đủ để bảo đảm các ca biên nghiệp vụ khớp backend. Hậu kiểm này bổ sung so sánh constraints, encoder BCrypt thật và luồng đăng ký qua Spring thật + database test.

## 2. Module Migration Status

| Phạm vi | Thay đổi | Trạng thái |
|---|---|---|
| Identity web/register | Tên reserved, Unicode, email quốc tế, timeout, khóa chỉnh dữ liệu trong request, thông báo resend trung lập | Implemented; browser tested |
| Password validation | Kiểm tra 72 **byte UTF-8** trước BCrypt cho các DTO dùng `@StrongPassword` | Implemented; unit tested |
| Users/Identity persistence | Hai cột email mở rộng tương thích 254 ký tự; owner và table names giữ nguyên | Implemented; PostgreSQL migration tested |
| Backend register/pending/privacy | Không đổi service; xác nhận không ghi đè identity/password pending khi retry | Integration tested |
| Các module khác | Không thay implementation | Full regression pass trong phạm vi suites hiện có |

## 3. Lỗi đã xác minh và cách sửa

| Phân loại | Lỗi/bằng chứng trước sửa | Sửa |
|---|---|---|
| Confirmed Bug | `deleted_123`/`DELETED_123` có tick hợp lệ nhưng backend cấm | Đồng bộ kiểm tra prefix case-insensitive ở form |
| Confirmed Bug | Mật khẩu Unicode hợp lệ phía Java bị client từ chối; control whitespace/NBSP cũng được hiểu khác | Dùng Unicode properties và tập Java whitespace tương ứng; giữ nguyên mật khẩu, không normalize/truncate |
| Confirmed Bug | Mật khẩu dưới 72 UTF-16 code units nhưng vượt 72 UTF-8 bytes qua validation rồi BCrypt ném exception | Chặn ở shared `StrongPasswordValidator` và form; trả validation 400 thay vì đi tới lỗi encoder |
| Confirmed Bug | Email quốc tế được `@Email` chấp nhận nhưng `input.validity.typeMismatch` từ chối | Client kiểm tra hình dạng cơ bản, không dùng HTML email grammar để siết thêm policy; backend vẫn kiểm tra đầy đủ |
| Confirmed Bug | API cho email tối đa 254, hai cột SQL chỉ 100; PostgreSQL test trước migration từ chối địa chỉ dài | Expand-only V20261108, đổi metadata hai entity lên 254; không sửa API limit |
| Confirmed Bug | Request treo: submit giữ disabled mãi; người dùng còn sửa email/password khi request cũ đang chạy | Deadline 30 giây, báo kết quả **chưa xác định**, mở lại form; readonly inputs trong request và single-flight chung register/resend |
| Confirmed Bug | Resend đọc `result.message` từ `data`, trong khi API thật trả `data:null`; fallback khẳng định đã gửi email | Bỏ giả định đó, dùng thông báo trung lập đúng anti-enumeration semantics |
| Confirmed state risk | DOM form đã gửi thành công có thể được history/BFCache khôi phục với nút disabled và inputs readonly | `pageshow.persisted` mở lại riêng form đã hoàn tất; không tự submit hoặc mở khóa request đang chạy |
| Already Correct | Retry cùng email đang pending không thay password/username đã giữ | Bảo toàn service; integration test xác nhận |

Browser baseline ghi nhận 9 mismatch trên bộ fixture; nút không phục hồi khi treo và email không readonly trong request. Baseline Java có 2 failure xác nhận validation/BCrypt lệch nhau. Các lỗi có sẵn được giữ lại trong form trước cũng được sửa trong lần hậu kiểm này; không gán tất cả lỗi là do thay UI mới tạo ra.

## 4. Changed Files / Backup

Runtime:

- `src/main/resources/static/js/auth-register.js`
- `src/main/resources/templates/auth/register.html` — help về byte limit, script version mới.
- `src/main/java/com/aloute/validation/StrongPasswordValidator.java`
- `src/main/java/com/aloute/model/entity/User.java`
- `src/main/java/com/aloute/model/entity/PendingUser.java`
- `src/main/resources/db/migration/V20261108__expand_account_email_length.sql`

Tests/operations:

- `src/test/resources/registration-validation-cases.json` — chỉ dữ liệu test tổng hợp.
- `src/test/java/com/aloute/validation/RegistrationBusinessValidationTest.java`
- `src/test/java/com/aloute/RegistrationBusinessIT.java`
- `src/test/java/com/aloute/schema/RegistrationEmailMigrationIT.java`
- `src/test/browser/registration-business.cjs`
- `src/test/browser/register-validation.cjs` — resend fixture đổi từ shape giả sang envelope trung lập `data:null` thật, giữ các assertion hành vi.
- `docs/operations/account-email-length-check.sql` — pre/postflight chỉ đọc số lượng/độ dài/index, không in email hoặc password.

Không xóa file; không đổi REST URL/method/JSON shape, JWT, STOMP, cache keys hoặc serializer. Không di chuyển package.

Backup trước hậu kiểm: `D:/aloute-develop123/aloute-quality-checkpoints/register-business-20261024-235240/`. Backup form trước thay UI ở `register-form-20261024-233445/` vẫn giữ nguyên. Không init/reset Git, không đọc/sao chép `.env` hay credentials production.

## 5. Security and Business Rules Preserved

- CSRF và backend authorization giữ nguyên. Frontend không tự quyết định email tồn tại, account được tạo hay mail đã được giao.
- Không sửa thuật toán hash, không truncate password, không đổi hash cũ. Giới hạn byte phản ánh giới hạn encoder đang dùng; chỉ các giá trị vốn làm encoder ném exception bị từ chối sớm. Login không áp dụng thêm policy từ DTO tạo mật khẩu.
- Request đăng ký vẫn đúng bốn trường, password giữ nguyên; readonly tạm thời tránh thay identity giữa lúc gửi. Hết timeout không tự retry hoặc báo chắc chắn server chưa xử lý.
- Email validation client chỉ là kiểm tra sơ bộ. Backend `@Email`/uniqueness/reserved names/rate limit vẫn authoritative; không hứa hai email grammars hoàn toàn tương đương cho mọi RFC edge case.
- Pending identity/password không bị thay bởi retry từ người biết email; thông báo register/resend không tiết lộ email có tài khoản hay không. Test mail adapter dùng mock, không gửi email thật.
- Không thay Feed ranking/privacy, Chat/Call, Notification delivery hoặc Media retention.

## 6. Test Results

| Suite | Kết quả |
|---|---|
| Baseline `RegistrationBusinessValidationTest` | 18 tổng; 16 pass, **2 fail trước sửa**, 0 skip |
| Baseline browser business | 9 mismatch; không phục hồi timeout; input chưa readonly |
| Sau sửa: targeted Maven verify | **42 unit + 2 PostgreSQL/Spring integration pass**, 0 fail/skip |
| Sau sửa: browser business | 17 validation cases khớp kỳ vọng; readonly, HTTP 400/retry, registration timeout, resend neutral/timeout, accepted → OTP, khôi phục form hoàn tất rồi submit mới pass |
| Sau sửa: browser UI | 8/8 dark/light × desktop/320×640 × normal/reduced-motion pass |
| `node --check auth-register.js` | Pass |
| Full `mvn -B verify` | BUILD SUCCESS, 7 phút 50 giây; **409 unit pass + 1 skip**, **59 integration pass + 2 skip**, 0 failure/error |

Skip: `AlouteApplicationTests` thiếu DB_URL theo điều kiện có sẵn; `DocumentPerformanceIT`, `PerformanceHttpIT` không bật benchmark opt-in vì không thay query/ranking. Không tính 3 bài này là pass.

Targeted command đã chạy:

```powershell
mvn -B '-Dtest=RegistrationBusinessValidationTest,RequestValidationTest,AuthServiceRegisterTest,AuthControllerWebTest' '-Dit.test=RegistrationBusinessIT,RegistrationEmailMigrationIT' '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/register-business-20261024-235240/temp -Duser.timezone=UTC' verify
node src/test/browser/registration-business.cjs
node src/test/browser/register-validation.cjs
```

Full command dùng cùng JVM options nhưng bỏ `-Dtest` và `-Dit.test`. Sau full verify có bổ sung riêng handler khôi phục history ở script; chạy lại browser business với 17 cases/4 registration requests thành công và đóng gói `mvn -B -DskipTests package`. Bước package không chạy lại tests. So sánh SHA-256 tài nguyên trong JAR với source cuối để tránh phát hành script cũ.

Ca history dùng DOM hoàn tất thật, phản hồi navigation 204 để giữ document, rồi phát `PageTransitionEvent('pageshow', {persisted:true})` và kiểm tra một POST mới thật sự được gửi. Đây là kiểm thử state restoration, **không phải** bằng chứng BFCache thực được bật trên mọi browser/thiết bị.

Maven/PG test chỉ dùng loopback `aloute_quality_datetime_20261024` và database migration random riêng `aloute_quality_registration_*`, Redis 56379. Business IT dùng production Spring wiring + MockMvc + PostgreSQL + BCrypt; EmailService được thay bằng mock. Transaction fixtures rollback. Migration IT tạo/drop đúng database do nó sở hữu, không gọi production. Browser chạy source thật với HTTP fixtures, không phải end-to-end với SMTP thật.

## 7. Migration và Performance

V20261108 chỉ tăng `users.email`/`pending_users.email` lên 254 nếu còn hẹp hơn; giữ nguyên kiểu text/unbounded hoặc cột rộng hơn. Migration test xác nhận lỗi cũ ở V07, fingerprint toàn bộ rows của hai bảng không đổi sau V08, email dài lưu nguyên vẹn, unique constraints còn chặn trùng và cột tùy biến 512 không bị thu hẹp khi replay DDL.

Rollout cần đi qua quy trình backup DB và preflight → Flyway → postflight bằng `account-email-length-check.sql`; kết quả count/index/độ dài phải được so sánh. **Chưa chạy migration trên production.** Không đo khóa bảng/p95 trên dữ liệu production và không tuyên bố hiệu năng.

Rollback ứng dụng có thể giữ nguyên cột đã mở rộng; không thu nhỏ lại 100 vì email dài có thể đã được lưu. Không rollback password/data, không xóa pending/account. Không tự deploy/restart instance người dùng.

## 8. Remaining Issues

- SMTP delivery, hỗ trợ email quốc tế ở mail provider thực, thiết bị/screen reader thật và race nhiều instance chưa kiểm chứng.
- Không tuyên bố toàn bộ frontend email/password validators ở các trang auth khác đã được đồng bộ. Shared backend byte guard áp dụng cho các DTO dùng annotation; client các trang khác nằm ngoài thay đổi form này.
- Toàn bộ kiến trúc backend trước đây vẫn PARTIAL; báo cáo này chỉ xác nhận phần hậu kiểm đăng ký.

## 9. Final Assessment

**Implemented + Tested trong phạm vi hậu kiểm đăng ký**. Full regression, business cases và migration test đã qua; provider/production/thiết bị thật vẫn NOT VERIFIED như mục 8. Bằng chứng lưu trong checkpoint và `docs/quality/registration-business-evidence/`. Không kết luận “không còn lỗi nghiệp vụ” cho toàn bộ dự án.
