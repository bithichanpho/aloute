# Avatar và duyệt yêu cầu theo dõi ở ba màn hình — 25/10/2026

## Audit và phạm vi

Yêu cầu trực tiếp: avatar trong danh sách yêu cầu; nút Chấp nhận/Từ chối trong Thông báo và trên hồ sơ người đang xin theo dõi viewer. Chỉ hiện hành động cho yêu cầu còn chờ duyệt của chính viewer.

| Phân loại | Bằng chứng | Xử lý |
|---|---|---|
| Confirmed Bug | Nhánh private của FriendService lưu request nhưng không enqueue notification | Enqueue FOLLOW_REQUEST trong cùng transaction hiện có |
| Confirmed Bug | UserDirectory projection và FollowRequestResponse thiếu avatar | Thêm trường public avatar, không tải credentials |
| Confirmed Bug | Activity chỉ có actions cho CLOSE_FRIEND_REQUEST; profile không có trạng thái yêu cầu nhận vào | Thêm actions dùng chung và incomingFollowRequestId |
| Already Correct | Quyết định follow xác thực recipient từ principal, kiểm tra targetId, dùng PUT/DELETE hiện có | Giữ endpoint và quyền hiện có, mọi màn hình gọi cùng API |
| Already Correct | Notifications có durable outbox, event ID dedupe, retry/backoff/dead letter | Dùng worker cũ; không thêm pipeline gửi khác |
| Requires Integration Testing | Chưa chạy đăng nhập/tạo request với database và FCM thật | Phân biệt tests doubles/fixture với live integration |

Git vẫn chưa có commit, source hiện có untracked, nhánh codex/follow-request-profile. Giữ thay đổi các tác vụ trước. Không stage/commit/push, đổi Redis, đọc .env/secrets, migration hoặc production deployment.

## Implemented / ownership

- Users: `users/api/UserDirectory.java`, `users/infrastructure/JpaUserDirectory.java`, `repository/IUserRepository.java`: projection immutable username/fullName/avatarUrl; batch giữ giới hạn 500. Constructor ProfileLabel hai tham số vẫn tương thích.
- Social: `social/api/FollowRequestLookup.java` và `social/infrastructure/LegacyFollowRequestLookup.java` mới; bridge tới `ContentAccessService.pendingFollowRequestIds`. Đọc request theo batch tối đa 100, loại request đã có follow edge bằng bulk query của `IFriendRepository`. Không truy vấn theo từng notification hoặc tải toàn bộ follow graph.
- Social: `FriendService.java` tạo intent FOLLOW_REQUEST khi tạo mới; rút request không tạo thêm intent. `SocialFeatureService.java`, `dto/response/FollowRequestResponse.java` trả avatar trong list/cursor, giữ constructor cũ.
- Users/profile: `UserService.java`, `dto/response/UserProfileDto.java` bổ sung incomingFollowRequestId theo chiều target → viewer, chỉ khi chưa có follow edge. Các flag follow gửi ra không bị thay thế.
- Notifications: `notifications/application/NotificationApplicationService.java`, `NotificationDeliveryWorker.java`, `service/NotificationService.java`, `dto/response/NotificationResponse.java`: batch profile labels sau visibility filter; thêm senderId/username/fullName/avatarUrl/followRequestId. Chỉ gắn action ID khi request đang tồn tại. Worker chuẩn bị pending references theo batch, giữ retry/dedupe cũ.
- Web composition: `controller/ViewController.java` bổ sung pending requests cũ chưa từng có notification vào Activity, không render lại request đã có action trong notification.
- UI: `templates/fragments/follow-request.html` mới; `templates/activity/index.html`, `templates/features/index.html`, `templates/profile/view.html` dùng chung nút; avatar/tên liên kết profile.
- UI: `static/js/features.js` dùng handler chung cho ba nơi, khóa cả hai nút khi gửi, xử lý cả cursor rows, retry khi lỗi tạm, bỏ nút khi 404, mark notification read sau thành công, reload profile để cập nhật quyền/counters. `static/js/app.js` giữ status trên API errors và refresh badge khi resolved. `static/css/features.css`, `templates/layout/base.html` thêm styles cần thiết và tăng asset version.

Không xóa file hay dữ liệu; không có database migration.

## Contract và an toàn

REST URLs/methods/status/envelope giữ nguyên. JSON chỉ thêm fields đã liệt kê; nullable fields có fallback ảnh mặc định. Public FOLLOW vẫn là theo dõi ngay, không biến thành yêu cầu duyệt. Yêu cầu private mới dùng type **FOLLOW_REQUEST** (mới), reference dạng `follower:recipient|createdAt`; timestamp xác định đúng lượt gửi, tránh outbox cũ được gửi lại sau khi request đã hủy rồi tạo mới. Không đổi ID document FollowRequest hoặc format lưu trữ.

Lookup chỉ trả các reference hiện còn pending; loại accepted edges kể cả request cleanup chưa hoàn tất. Worker kiểm tra active/block trước gửi. Request đã rút hoặc reference của lượt gửi cũ không tạo push/notification mới khi xử lý muộn. Row notification lịch sử giữ nguyên; nút không còn hiện nếu không còn quyền xử lý.

Nút ở hồ sơ xử lý yêu cầu người đó gửi cho chính viewer, không cấp quyền xử lý yêu cầu của người khác. Quyền quyết định vẫn nằm tại backend; dữ liệu userId trên DOM không thay thế principal. Privacy của bài viết/ảnh/repost vẫn giữ gate đã sửa trước đó. Không đưa nội dung private hoặc credentials vào payload.

## Kiểm thử đã chạy

Lượt đầu 47 tests có 1 failure và 1 error vì fixtures cũ cấm profile lookup và chưa đăng ký dependency FollowRequestLookup mới. Đã cập nhật đúng hành vi chủ đích: một profile projection sau visibility filter, bean read-port được wiring. Lượt đó có source đang thay đổi nên **không coi là baseline thuần trước sửa**.

Scoped gate sau sửa: **69 tests PASS**, 0 failures/errors/skips, gồm ModuleBoundaryTest (12 architecture tests), templates, API authorization, profile query budget, batch user directory, notification delivery/retry, request generation, canceled/accepted request lookup. Có bổ sung REST response contract và worker batch contract sau scoped gate; kết quả toàn bộ suite được ghi ở phần cập nhật cuối báo cáo.

Browser Edge headless + Playwright runtime có sẵn, API fixtures:

- `node src/test/browser/follow-request-profile.cjs`: **2 viewport scenarios PASS**, 1280/390; avatar, fallback, profile link, cursor/duplicate page, text escaping, quyết định và xóa cả dòng.
- `node src/test/browser/follow-request-actions.cjs`: **8 scenarios PASS**; accept/reject tại settings/activity/profile, lỗi 503 thử lại, stale 404; hai nút khóa đồng thời, chỉ một mutation khi bấm cả hai, notification read, profile reload.
- `node src/test/browser/private-profile-follow.cjs`: **3 scenarios PASS**; giữ regression private-unfollow/private-pending/public-follow.

Tests mới: `service/FollowRequestWorkflowTest.java`, `notifications/FollowRequestNotificationTest.java`, `browser/follow-request-actions.cjs`. Tests mở rộng: FeatureTemplateTest, PrivateProfileTemplateTest, UserDirectoryTest, UserProfileQueryBudgetTest, NotificationApplicationServiceTest, NotificationWiringTest, NotificationOutboxWorkerTest và browser follow-request-profile.

Browser test lượt đầu nhận HTML thay cho hai thư viện JS tự tải trong fixture, gây Unexpected token '<'. Đã sửa fixture trả JavaScript rỗng cho các thư viện ngoại vi không thuộc scope; app.js và features.js vẫn là source thật. Không thay mã production để che lỗi fixture.

Không kiểm chứng FCM/live WebSocket session/live database transaction hoặc concurrent accept từ nhiều process; không khẳng định exactly-once external delivery. Không đo latency/load/EXPLAIN; chỉ kiểm tra bulk query count qua doubles.

## Áp dụng và rollback

Khởi động lại backend theo quy trình STS hiện có rồi tải lại trình duyệt để nhận Spring wiring và asset version mới. Không cần migration hoặc sửa dữ liệu; request cũ vẫn hiện ở Activity nhờ composition từ pending requests.

Rollback source của lát cắt này và rebuild/restart; giữ schema và mọi dữ liệu. Không dùng git reset/clean khi toàn bộ source chưa được commit. Các pending outbox event FOLLOW_REQUEST dùng pipeline hiện có; không xóa pending events/notifications để rollback.

Trạng thái: **Implemented, Partially Tested** — unit/architecture/REST/templates/browser fixtures đã kiểm chứng; live account/DB/FCM chưa kiểm chứng.

## Kết quả gate cuối

`mvn -o -q test` hoàn tất exit 0: **455 tests tổng, 454 PASS, 0 failures, 0 errors, 1 skipped**. Suite bỏ qua là `AlouteApplicationTests`, chỉ được bật khi có DB_URL trong environment; không cung cấp credentials DB cho tác vụ này. Hai contract cases bổ sung đã chạy trong full suite. Các log ERROR/WARN của outbox/rate-limit/moderation xuất phát từ tình huống lỗi được chủ động giả lập trong regression tests, không phải lỗi kết nối Redis của backend đang chạy.

`node --check` cho app.js/features.js PASS. Tổng browser fixture scenarios: **13 PASS** (2 + 8 + 3 như trên). Không chạy migration hoặc viết dữ liệu test vào tài khoản thật.
