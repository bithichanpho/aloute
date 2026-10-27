# Yêu cầu theo dõi đã xem không nhắc lại — 27/10/2026

Trạng thái: Implemented / Partially Tested.

## Audit và hành vi

- Confirmed Bug: API unread-count và badge đang đếm toàn bộ yêu cầu đang chờ, không có trạng thái đã xem. Frontend còn có thể phục hồi số cũ từ cache/poll khi vừa mở trang.
- Khi mở trang /follow-requests, backend đánh dấu các yêu cầu chưa xem hiện có của chính principal là đã xem, trước khi tải danh sách. Chỉ cập nhật seenAt; không chấp nhận, từ chối, xóa yêu cầu hoặc ghi quan hệ follow.
- Số nhắc chỉ đếm yêu cầu có seenAt null. Dữ liệu cũ chưa có field này được xem là chưa đọc; yêu cầu mới mặc định chưa đọc. Danh sách/cursor vẫn trả đầy đủ yêu cầu chờ.
- Thêm event REQUESTS_SEEN trên kênh user-scoped hiện có sau commit để tab khác tải lại số nhắc. Event không thay đổi follow status, không mang danh tính người gửi và không tạo popup/toast.
- Giao diện bỏ số cache cũ khi vào trang, buộc lấy số mới và bỏ kết quả poll thuộc phiên đọc trước. Không ngăn yêu cầu mới phát sinh sau lượt xem hiển thị số nhắc.

## Lưu trữ và contract

Thêm field JSONB tùy chọn FollowRequest.seenAt; không thêm bảng, migration hoặc backfill. Dùng một updateMulti giới hạn targetId và seenAt null, một SQL aggregate để đếm; không tải toàn bộ dataset vào RAM. DTO danh sách và JSON shape unread-count giữ nguyên. countRequests vẫn giữ ý nghĩa đếm tất cả để tương thích; UI/API unread dùng countUnreadRequests.

File chính: FollowRequest.java; SocialFeatureService.java; ViewController.java; SocialFeatureController.java; FollowRequestRealtimePublisher.java; app.js; features.js; layout/base.html (phiên bản tài nguyên).

## Kiểm chứng thực chạy

- Baseline SocialFeatureServiceTest + ActivityPageTest: 18/18 đạt trước thay đổi.
- Sau thay đổi:

```powershell
mvn --batch-mode '-Dtest=SocialFeatureServiceTest,ActivityPageTest,FollowRequestInboxTest,FollowRequestSeenEventTest,FeatureTemplateTest,NotificationApplicationServiceTest' test
```

54/54 đạt. Kiểm chứng scope người nhận, field cập nhật duy nhất, event sau commit, không phát lại khi không có thay đổi, legacy JSON/serialization, principal thay vì userId query và không cho anonymous xem/đánh dấu inbox. Test ActivityPageTest được cập nhật có chủ đích để badge đếm chưa xem thay vì tất cả đang chờ.

- follow-request-seen.cjs: 4/4 workflow đạt (desktop/mobile × poll bình thường/về chậm), gồm mở xem, reload giữ danh sách, yêu cầu mới vẫn báo, tab khác xem xóa số nhắc và không tạo toast.
- private-follow-workflow.cjs: 16/16 đạt; chấp nhận/từ chối, retry, stale request, tên/avatar, realtime và private profile giữ nguyên.
- JavaScript syntax và git diff --check đạt. Migration không thay đổi.

Chưa kiểm tra PostgreSQL và STOMP thật; browser dùng HTTP/STOMP fixtures, Java persistence qua test doubles. Không ghi dữ liệu người dùng thật, chạy migration hoặc khởi động lại backend trong lượt sửa này. Cần khởi động lại backend và tải lại trang để dùng bản sửa.

## Phục hồi riêng thay đổi

Nếu cần, hoàn tác các phần xử lý seen/unread và event mới trong những file trên; field JSONB seenAt có thể để lại, code cũ không cần backfill. Không reset toàn file vì còn các sửa khác. Không thực thi rollback/reset trong lượt này.
