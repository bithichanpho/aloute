# Sửa lỗi source hiện tại — 26/10/2026

## Phạm vi và nguyên nhân đã xác minh

Sửa trực tiếp repository hiện tại, không phục hồi source từ ZIP. Báo cáo đối chiếu ZIP là baseline **trước sửa**. Một số adapter/tests mới có mặt nhưng implementation tương ứng chưa có: compile main mới từ 335 source files lỗi ở `ContentAccessService.pendingFollowRequestIds`; testCompile lỗi 23 lần tham chiếu API/field thiếu. Đây là lỗi đồng bộ mã nguồn, không phải bằng chứng Redis làm hỏng source.

## Implemented

| Phần | Sửa đổi | Bảo toàn |
|---|---|---|
| Users | `UserDirectory.ProfileLabel`, truy vấn projection tên/avatar theo batch tối đa 500 ID, không tải credentials | Lookup username/existence cũ |
| Social | Trả tên/avatar trên danh sách/cursor yêu cầu; kiểm tra pending theo generation và loại quan hệ đã được chấp nhận bằng truy vấn batch | ID, trường cũ, cursor keyset và quyền quyết định của người nhận |
| Notifications | Intent `FOLLOW_REQUEST` qua outbox đang có; recheck pending trước gửi; enrich response sau lọc visibility; hỗ trợ thông báo FOLLOW cũ | Retry, outboxEventId dedupe, REST envelope, WebSocket destination và trường cũ |
| Profile/privacy | Kiểm tra quyền xem nội dung trước cả bốn hàm timeline/repost list/cursor; trả ID yêu cầu chiều ngược để chủ tài khoản có thể duyệt trên trang người gửi | Profile cơ bản, quyền của owner/current follower, kiểm tra quyền bài gốc |
| UI | Tên/avatar/link hồ sơ trong settings và activity; nút duyệt dùng chung cả ba vị trí; khóa hai nút khi xử lý; retry/404; thông báo riêng tư một dòng; ẩn nội dung/loaders với người chưa được duyệt | Giao diện/framework hiện có và các xử lý bạn thân |
| Chat | Giữ `callDurationSeconds` nullable khi đọc/ghi JSONB | Document cũ thiếu duration vẫn đọc được; không migration |
| Realtime assets | Chuẩn hóa CRLF về LF ở hai thư viện vendored | SHA-256 khớp chính xác bản đã ghi trong REALTIME-NOTICE; không đổi phiên bản/API |

`Already Correct`: Feed cache hit đã tải lại cards theo quyền hiện tại; giữ nguyên implementation đó cùng các tối ưu feed/query đang có. Không sửa thuật toán xếp hạng, schema database, cấu hình Redis hoặc deployment.

## Files sửa

Java main (dưới `src/main/java/com/aloute/`):

- `users/api/UserDirectory.java`, `users/infrastructure/JpaUserDirectory.java`
- `repository/IUserRepository.java`, `repository/IFriendRepository.java`
- `service/ContentAccessService.java`, `service/FriendService.java`, `service/SocialFeatureService.java`
- `service/PostService.java`, `service/UserService.java`, `service/NotificationService.java`
- `notifications/application/NotificationApplicationService.java`, `notifications/application/NotificationDeliveryWorker.java`
- `dto/response/UserProfileDto.java`, `dto/response/NotificationResponse.java`
- `model/document/ChatMessage.java`, `controller/ViewController.java`

Resources (dưới `src/main/resources/`):

- `templates/profile/view.html`, `templates/features/index.html`, `templates/activity/index.html`, `templates/layout/base.html`
- `static/js/app.js`, `static/js/features.js`, `static/css/features.css`
- `static/vendor/sockjs-client/sockjs.min.js`, `static/vendor/stomp/stomp.min.js` (chỉ line endings)

Tests sửa (dưới `src/test/java/com/aloute/`):

- `users/UserDirectoryTest.java`, `service/SocialFeatureServiceTest.java`, `service/UserProfileQueryBudgetTest.java`
- `service/FeedQueryBudgetTest.java`, `FeatureTemplateTest.java`
- `notifications/NotificationApplicationServiceTest.java`, `notifications/NotificationWiringTest.java`, `notifications/FollowRequestNotificationTest.java`

Các test privacy/workflow/controller/browser/JSON compatibility đã có trong workspace cũng được chạy lại. Fixture test được cập nhật để cung cấp dependency lookup mới, cho phép profile owner trong mock access, và xác nhận một lần query public labels sau lọc visibility; không bỏ kiểm tra bảo mật hay giảm assertion để che lỗi.

## Tested

- Compile main thật: 335 Java source files — PASS, không dựa vào classes cũ.
- `mvn -o -B package` với giới hạn heap/metaspace, `DB_URL` không truyền vào test process: **BUILD SUCCESS**.
- Kết quả cuối: **454 tests được phát hiện, 453 PASS, 0 failure, 0 error, 1 skipped**. Bao gồm 12 architecture tests, private/pending/removed follower, REST principal spoofing, notification retry/cancel/generation, JSON duration, projection batching và template render.
- Artifact: `target/aloute-0.0.1-SNAPSHOT.jar` được đóng gói lại thành công.
- `node --check` 15 application JavaScript files: PASS.
- Browser fixtures dùng Edge headless và Playwright: 13 tình huống PASS (8 actions, 2 viewport danh sách/avatar/pagination, 3 trạng thái follow). Bao gồm accept/reject trên settings/activity/profile, bấm ngược liên tiếp, lỗi 503 thử lại, 404 hết hiệu lực và refresh nội dung riêng tư sau unfollow.
- Log cuối: `target/current-fix-package-20261026.log`. Lượt kiểm tra trước đó: `target/current-fix-tests-20261026.log`, `target/current-fix-tests-20261026-final.log`.

## Partially Tested / Not Started

- `AlouteApplicationTests.contextLoads` skipped theo điều kiện `DB_URL` của test. Các integration test tên `*IT` không nằm trong default Maven test discovery; chưa chạy DB/Redis/WebSocket/FCM thật.
- Không có listener local 8081/6379 khi kiểm tra cuối; chưa xác minh bằng phiên đăng nhập thật hoặc xác nhận Redis reconnect. Browser fixtures sử dụng API giả lập, không phải smoke test live backend.
- Không load test/đo p95; không tuyên bố cải thiện hiệu năng. Không chạy migration, xóa dữ liệu, push/deploy hoặc chỉnh external infrastructure.

Trạng thái: **Implemented + Tested cho compile/package và các regression đã nêu; PARTIAL cho kiểm chứng toàn hệ thống có dịch vụ thật**. Khi chạy backend lại, cần nạp bản build mới và kiểm tra bằng hai tài khoản thật; các asset UI đã đổi version để tránh cache cũ.

Rollback: khôi phục đúng các thay đổi source của lát cắt này hoặc dùng artifact trước sửa trong môi trường kiểm thử; không ghi đè toàn bộ workspace và không reset dữ liệu. Không có schema migration cần đảo ngược.
