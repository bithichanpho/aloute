# Sửa luồng đăng xuất — 24/10/2026

## 1. Verified Architecture Snapshot

- Spring Boot 4.1.1, Java target 21; Maven chạy bằng JDK 22 trong môi trường này.
- Cookie JWT `ALOUTE_TOKEN` (HttpOnly, `/`), refresh token `ALOUTE_REFRESH` (HttpOnly, `/api/auth`); CSRF double-submit cookie `XSRF-TOKEN`/header `X-XSRF-TOKEN`.
- Refresh token được thu hồi trong PostgreSQL; access token dùng denylist Redis/local theo cấu hình hiện hữu. Không đổi cơ chế lưu trữ hay chính sách fail-closed.
- Nút desktop/mobile được `app.js:initLogout` xử lý, gọi `POST /api/auth/logout`; `/login` chuyển người đang đăng nhập về `/feed`.
- Workspace không có Git metadata; giữ nguyên hiện trạng, không init/reset. Checkpoint trước sửa và log ở `D:/aloute-develop123/aloute-quality-checkpoints/20261024/logout/`.

## 2. Module Migration Status

| Phạm vi | Trước sửa | Thay đổi | Trạng thái |
|---|---|---|---|
| Identity: backend logout | Đã thu hồi access/refresh và hết hạn cookie | Giữ implementation, thêm regression qua Spring Security và PostgreSQL test | Tested |
| Web: desktop/mobile logout | Chờ push cleanup vô hạn; nuốt lỗi logout; có thể bấm trùng/chồng refresh | Deadline push 2 giây, toàn thao tác 10 giây; lỗi hiển thị và thử lại; một thao tác chung cho cả hai nút; đợi refresh đang chạy, chặn refresh mới | Implemented; browser tested |
| Feed, Social, Users, Posts, Communities, Messaging/Calls, Notifications, Media/Moderation, Administration | Giữ implementation hiện có | Không refactor thêm trong yêu cầu sửa logout | Không tuyên bố hoàn thành toàn bộ modularization |

## 3. Dependency / Architecture Violations

Không thêm cross-module dependency, repository API, infrastructure hoặc event mới. Backend identity tiếp tục là owner thu hồi phiên; browser chỉ gửi yêu cầu và điều hướng sau khi được xác nhận.

Các lỗi đã tái hiện với mã trước sửa bằng Playwright:

| Phân loại | Bằng chứng | Sửa |
|---|---|---|
| Confirmed Bug | Push DELETE không trả kết quả: số POST logout bằng 0 | Abort cleanup sau 2 giây rồi tiếp tục logout |
| Confirmed Bug | Logout trả 403: chuyển `/login` nhưng không hiển thị lỗi; cookie chưa bị xóa có thể làm `/login` redirect ngược Feed | Chỉ điều hướng khi logout thành công; hiển thị lỗi và cho thử lại |
| Confirmed Bug | Bấm desktop/mobile liên tiếp: gửi 2 POST logout | Trạng thái dùng chung và `aria-busy` |
| Confirmed race | POST logout được gửi khi refresh cũ còn chạy | Đợi rotation trước, chặn rotation mới trong cùng page; operation hết hạn không được chạy tiếp khi refresh trả về muộn |
| Already Correct | Cookie path và server-side revocation đã có | Không thay endpoint hay nới CSRF |

Đây là lỗi xác minh từ source và môi trường test. Không có request trace từ trình duyệt đang gặp lỗi của người dùng, nên chưa quy nguyên nhân cho cấu hình proxy/cookie của instance đó.

## 4. Changed Files

- Sửa `src/main/resources/static/js/app.js`: xử lý logout, deadline, refresh coordination và lỗi.
- Sửa `src/main/resources/templates/layout/base.html`: phiên bản URL script `20261024-logout-v1` để tải mã mới.
- Sửa `src/test/java/com/aloute/FullBackendContextIT.java`: thêm CSRF, token/cookie/replay và session isolation regression.
- Thêm `src/test/browser/logout-regression.cjs`: browser tests dùng chính source `app.js` và HTTP fixtures; không gọi tài khoản/dịch vụ production.
- Báo cáo và evidence tại `docs/quality/`.
- Không xóa file; không migration; không đổi REST/JSON/WebSocket contract, schema, ranking hay post data.

## 5. Security and Business Rules Preserved

- CSRF vẫn bắt buộc với logout. Không thêm GET để thực hiện logout; không tự xóa HttpOnly cookie bằng JavaScript hay chuyển trang để giả lập thành công.
- Access denylist, refresh revocation, cookie paths/HttpOnly/SameSite/Secure dùng cơ chế sẵn có. Không đăng xuất tất cả thiết bị thay cho thiết bị hiện tại.
- Dọn push vẫn best effort như trước, có giới hạn chờ. Đặt opt-out để tránh đăng ký nền khi đang logout. Không khẳng định Firebase đã xóa installation khi dịch vụ không phản hồi.
- Nếu logout lỗi hoặc hết thời gian, hiển thị thông báo, mở lại nút để thử lại. Timeout không chứng minh server chưa xử lý; retry dùng endpoint idempotent hiện có.
- Phối hợp refresh giới hạn trong cùng JavaScript page. Chưa thay refresh-token family hoặc bổ sung đồng bộ cross-tab/multi-instance.
- Không đổi auth/privacy Feed, block/mute/group, Chat ownership, call state, media retention. Không dùng production DB, credentials, migration hoặc deployment.

## 6. Test Result

- Baseline: `AuthControllerWebTest,RefreshTokenServiceTest`: 13/13 pass trước sửa.
- Browser trước sửa: tái hiện 5 tình huống, lưu `browser-before.log` trong checkpoint.
- Browser sau sửa: 8 tình huống pass: 403/thử lại, network error, storage bị chặn, push treo, logout thành công/bấm trùng/không refresh nền, refresh đang chạy, refresh treo/trả muộn, logout timeout. CSRF header được giữ.
- Search browser regression và `node --check app.js`: pass.
- Đóng gói lại bằng `mvn -B -DskipTests package`: BUILD SUCCESS sau kiểm thử; bước này không chạy lại tests. Đối chiếu SHA-256 xác nhận `app.js` và template trong JAR trùng source cuối, gồm cả push opt-out đã kiểm thử browser.
- `mvn -B verify`: BUILD SUCCESS, 5 phút 18 giây; unit 392 tổng = 391 pass + 1 skip, integration 59 tổng = 57 pass + 2 skip, không failure/error. Bao gồm architecture, Feed, Chat/Call recovery, temporal JSONB, Redis và WebSocket browser smoke.
- Skip có chủ đích: `AlouteApplicationTests` thiếu biến DB_URL theo điều kiện sẵn có; `DocumentPerformanceIT` và `PerformanceHttpIT` là benchmark opt-in, không bật lại cho thay đổi logout. Đây không phải 3 bài pass.
- Test mới `FullBackendContextIT.logoutRequiresCsrfExpiresCookiesAndRejectsAccessAndRefreshReplayWithoutRevokingOtherSessions` pass qua MockMvc + security chain thật + PostgreSQL/Redis test: thiếu/sai CSRF bị từ chối, cookie hết hạn với đúng path/HttpOnly, token cũ không được dùng lại, phiên refresh khác được giữ. Không phải browser end-to-end trực tiếp vào instance của người dùng.
- Lần integration đầu có assertion 403 nhận status 200 vì MockMvc chưa gán servletPath: security handler chọn nhánh HTML forward. Đã sửa fixture gửi servletPath API thật, giữ nguyên assertion và production security.
- PostgreSQL test là database loopback `aloute_quality_datetime_20261024`; Redis test loopback 56379. Dữ liệu fixture logout nằm trong transaction rollback.
- Chưa xác minh trực tiếp instance/trình duyệt của người dùng, provider Firebase thật, hay race logout/refresh trên nhiều tab/máy chủ.

Evidence: `docs/quality/logout-evidence/` lưu kết quả browser trước/sau, search regression, tổng hợp Maven và SHA-256 các file runtime trước/sau. Log đầy đủ lưu ngoài repository trong checkpoint nêu trên.

Trạng thái: **Implemented + Tested trong môi trường test** cho bản sửa logout; **Not Verified** trên instance đang chạy của người dùng. Tác vụ kiến trúc toàn backend trước đây vẫn PARTIAL; không đổi trạng thái thành DONE từ bản sửa này.

Rollout: nạp lại bản build chứa script mới và template mới; không cần migration. Không tự khởi động lại server của người dùng. Rollback chỉ khôi phục hai file runtime từ checkpoint; không rollback dữ liệu hoặc các thay đổi trước yêu cầu này.
