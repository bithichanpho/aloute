# Khôi phục đầy đủ private — 26/10/2026

## Audit source hiện tại

Confirmed Bug: source lại thiếu `profileContentVisible`, public labels/avatar, pending request lookup, FOLLOW_REQUEST delivery và các nút duyệt trong Activity/profile. Các file test/browser của lát cắt trước cũng không còn. Không suy diễn ai hoặc thao tác nào đã thay source.

Already Correct: quyền đọc từng bài và feed cache hit đã kiểm tra active/block/private/current follow bằng backend; giữ implementation, cursor, query optimizations và ranking đang có.

## Implemented

- **Private content:** bốn đường timeline/repost list/cursor kiểm tra quyền chủ profile trước query. Owner/current approved follower được xem; outsider, anonymous, request pending và follower đã bị gỡ không được xem. Quyền của bài gốc vẫn áp dụng cho repost.
- **Profile UI:** ẩn timeline/grid/repost cùng loaders với viewer chưa có quyền. Chỉ hiện một dòng “Đây là tài khoản riêng tư.”. Thay đổi follow trên profile private tải lại trang để loại nội dung cũ.
- **Danh tính người gửi:** Users cung cấp immutable public projection tên/username/avatar theo batch 500; requests cursor chỉ enrich trang trả về, không enrich lookahead hoặc query theo từng người.
- **Duyệt ba nơi:** tên/avatar/link account trong settings/Activity; profile của requester có nút duyệt yêu cầu người đó gửi cho viewer. Một fragment/handler dùng chung PUT/DELETE cũ. Khóa cả hai nút khi gửi, có retry/404, mark notification read và cập nhật badge.
- **Notification/outbox:** intent FOLLOW_REQUEST đi qua outbox cũ; generation `requestId|createdAt` ngăn gửi yêu cầu cũ đã hủy/tạo lại. Bulk pending lookup loại accepted edges. Giữ retry và outbox event dedupe. Activity bổ sung request cũ chưa có notification và không lặp lại những request đã có nút trong notification.

Các endpoint/method/envelope và trường cũ giữ nguyên; chỉ bổ sung public response fields và notification type mới có chủ đích. Không đổi schema, chạy migration, sửa Redis hoặc ghi dữ liệu người dùng.

## File và ownership

- Users: `UserDirectory`, `JpaUserDirectory`, projection trong `IUserRepository`.
- Social: `FollowRequestLookup`, `LegacyFollowRequestLookup`, `ContentAccessService`, `FriendService`, `SocialFeatureService`, bulk query trong `IFriendRepository`, `FollowRequestResponse`.
- Profile/Posts: `PostService`, `UserService`, `UserProfileDto`, `ViewController`.
- Notifications: application service, delivery worker, compatibility facade, response DTO.
- UI: templates profile/settings/activity/base và fragment mới; app.js/features.js/features.css. Asset version mới `20261026-private-restored`.
- Tests: bốn JUnit classes mới (private content, private template, request workflow, notification workflow); mở rộng bảy class test có sẵn; một browser fixture tổng hợp.

Danh sách chính xác 38 source/test files kèm SHA-256: `target/private-full-restored-manifest-20261026.json`. Backup riêng đã kiểm SHA từng entry: `backups/private-full-restored-20261026.zip`. Backup không chứa `.env`, config credentials, target build hoặc database data. Đây là bản sao source sau sửa; không tự giải nén chép đè workspace.

## Tested

- `mvn -o -B package` (memory capped, không truyền DB_URL vào test): **BUILD SUCCESS; 444 tests tổng, 443 PASS, 0 failures/errors, 1 skipped**. Context test có điều kiện DB_URL bị skip; không giả là integration pass.
- Bao gồm architecture boundary tests, private owner/approved/pending/removed/anonymous/block/inactive cases, quyền bài gốc, REST viewer spoofing, cursor batching, label projection, inbound profile request, templates và notification cancellation/generation/retry/dedupe.
- `node src/test/browser/private-follow-workflow.cjs`: **13 tình huống PASS**, dùng actual application JS/CSS với API fixtures. Tám quyết định/retry/stale ở settings/activity/profile, hai viewport avatar/name/fallback/pagination/XSS/profile link, ba trường hợp private unfollow/private pending/public follow.
- `node --check`: 15 application JS files PASS.
- Kiểm tra JAR mới có class/fragment private và cổng local **8081**. Log Maven: `target/private-full-restore-20261026.log`.

## Giới hạn và áp dụng

Chưa thử luồng thay đổi dữ liệu bằng hai tài khoản thật trên Render/FCM/WebSocket. Không tự khởi động lại backend kết nối database dùng chung hoặc chạy migration. Sau sửa cần chạy lại backend trong IDE và tải lại trình duyệt để nạp bean/template/assets mới.

Trạng thái: **Implemented / Tested với unit, architecture, REST doubles, templates, browser fixtures; Partially Tested với môi trường dịch vụ thật**. Không sử dụng tổng test của lượt sửa trước để báo kết quả lần này.
