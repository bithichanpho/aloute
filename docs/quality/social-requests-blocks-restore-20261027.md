# Khôi phục yêu cầu chờ và tài khoản chặn — 27/10/2026

## Phạm vi đã xác nhận

Người dùng xác nhận khôi phục avatar, tên/@username và Chấp nhận/Từ chối cho yêu cầu theo dõi; avatar, tên/@username và Bỏ chặn cho danh sách chặn. Nguồn tham khảo: nhánh được giữ lại `codex/current-code-20261027`; triển khai chọn lọc trên `develop-ptrang` tại nền `7251dd9`. Không chép đè toàn bộ nhánh cũ.

## Audit và thay đổi

- Confirmed Bug: API trả FollowRequest/UserBlock thô, cursor rows chỉ có ID; template chặn hiển thị Tài khoản #...; thao tác duyệt dùng closest(data-request-id) có thể xóa nút thay vì cả dòng.
- Implemented: DTO FollowRequestResponse/BlockedAccountResponse bổ sung nhãn công khai; Users sở hữu projection ID/username/fullName/avatar, batch tối đa 500, không tải credentials. Social chỉ lấy nhãn cho các dòng của trang đã phân trang size+1.
- Implemented: trang /follow-requests và Cài đặt có avatar/tên/@username; cùng fragment và handler cho Chấp nhận/Từ chối tại Cài đặt, Hoạt động và hồ sơ người xin theo dõi. Hoạt động compose từ pending requests persisted, không tạo thêm pipeline notification.
- Implemented: pending request trên profile theo chiều target -> viewer, bỏ actions khi đã có follow edge, giữ kiểm tra profile/block trước mọi lookup request.
- Implemented: chặn có avatar/tên/@username, nhắc bỏ chặn trước khi mở hồ sơ; lỗi DELETE giữ hàng để thử lại, thành công mới xóa và cập nhật trạng thái trống.
- Implemented: khóa hai nút khi xử lý, tránh mutation lặp, xử lý stale 404, giữ HTTP status trong API error; cập nhật badge theo event cũ; fetch realtime chậm không dựng lại request đã hủy/đã duyệt.
- Already Correct: principal và quyền recipient tại backend, block gates, cursor/sort, CSRF và avatar fallback. Giữ các checks này và kiểm chứng hồi quy.

## Contract và dữ liệu

Giữ URL/method API, numeric profile route /profile/{id}, STOMP destination/payload hiện tại và định dạng lưu FollowRequest/UserBlock. DTO chỉ chứa ID, owner/recipient, thời gian và public labels; không trả reconciliation state/credentials. Không khôi phục 2FA, Bạn thân, handle routes hoặc thuật toán feed ngoài yêu cầu. FriendService vẫn giữ pipeline hiện tại (không tạo Activity notification cho private request); Activity lấy request đang chờ trực tiếp để hiện actions. Không đổi notification/outbox delivery hoặc migration.

Không đọc .env/secrets, khởi động backend hoặc thao tác database thật. Các sửa ChatMessage/Docker từ lượt trước được giữ. Phát hiện server.port đã được đổi sang 8081 trong lúc làm việc; giữ thay đổi đó, không ghi đè.

## File

Mới: dto/response/FollowRequestResponse.java, dto/response/BlockedAccountResponse.java, templates/fragments/follow-request.html, src/test/browser/private-follow-workflow.cjs, src/test/browser/blocked-accounts-workflow.cjs và báo cáo này.

Sửa Java: users/api/UserDirectory.java, users/infrastructure/JpaUserDirectory.java, repository/IUserRepository.java, service/SocialFeatureService.java, service/UserService.java, service/NotificationService.java (chỉ compatibility adapter cho method directory mới), dto/response/UserProfileDto.java, controller/ViewController.java.

Sửa UI: templates/features/index.html, templates/follow-requests/index.html, templates/activity/index.html, templates/profile/view.html, templates/layout/base.html (asset versions), static/js/features.js, static/js/app.js (HTTP error status), static/css/features.css.

Tests Java: FeatureTemplateTest, SocialFeatureServiceTest, UserDirectoryTest, UserProfileQueryBudgetTest. So sánh block entity trong test cũ được đổi sang stable ID vì response chủ đích chuyển sang public DTO; giữ kiểm tra cursor/hasMore. Browser fixtures cũ được thích ứng với route /profile/{id} của nhánh này; GET unread-count không được tính là PUT/DELETE mutation. Không sửa assertion để bỏ qua lỗi production.

## Kiểm chứng thực tế

- Baseline trước thay đổi social: 25 tests Java đạt, không lỗi/bỏ qua.
- Gate social ban đầu: 34 tests đạt. Gate mở rộng sau đó: 58 tests, 57 đạt, 1 error trong NotificationApplicationServiceTest.notificationPageUsesOneBatchVisibilityCheckAndRetainsCursor: immutable Set.contains(null) tại NotificationApplicationService.java:237. Class application và test này không được sửa trong tác vụ; không tuyên bố toàn bộ suite đạt.
- Gate tập trung cuối gồm SocialFeatureServiceTest, SocialFeatureAccountModerationTest, UserDirectoryTest, FeatureTemplateTest, UserProfileQueryBudgetTest, FriendServicePrivateFollowRequestTest, PaginationControllerTest, NotificationWiringTest, NotificationServiceDeliveryTest, ChatMessageJsonCompatibilityTest: 42 tests đạt, 0 failures/errors/skips; BUILD SUCCESS.
- Architecture gate đã chạy: 12 tests, 10 đạt, 2 lỗi ở Notifications.notifyPostModerationResult phụ thuộc trực tiếp model.document.ModerationStatus. Các file gây lỗi không được sửa; không suppress hoặc thay architecture test.
- Browser private-follow-workflow.cjs: 13 kịch bản đạt, bao gồm desktop/mobile, ba nơi duyệt, retry, stale request, text escaping, cursor labels, realtime create/cancel, delayed cancellation và delayed local decision.
- Browser blocked-accounts-workflow.cjs: 4 kịch bản đạt tại 1280/390px và sáng/tối; dùng HTML Thymeleaf đã render cùng JS/CSS thật, API fixture. Đã xem ảnh mobile sáng.
- JavaScript syntax và git diff --check đạt. Migration diff với HEAD rỗng.

Lệnh Java cuối: `mvn --batch-mode '-Dtest=SocialFeatureServiceTest,SocialFeatureAccountModerationTest,UserDirectoryTest,FeatureTemplateTest,UserProfileQueryBudgetTest,FriendServicePrivateFollowRequestTest,PaginationControllerTest,NotificationWiringTest,NotificationServiceDeliveryTest,ChatMessageJsonCompatibilityTest' test`.

Browser dùng Playwright runtime có sẵn, Edge headless, ALOUTE_PLAYWRIGHT_MODULE và ALOUTE_BROWSER_EXECUTABLE; không ghi tài khoản thật. Chưa kiểm thử hai tài khoản thật, database transaction, FCM hoặc STOMP broker thật; chưa đo hiệu năng. Trạng thái: Implemented / Partially Tested.

## Áp dụng và rollback

Khởi động lại backend rồi Ctrl+F5. Mở /follow-requests hoặc /settings#settings-requests, /settings#settings-blocked. Rollback chọn lọc nhóm Java/DTO/template/JS/CSS và version assets cùng nhau; không reset toàn repo, không rollback các sửa lỗi khởi động trước đó hoặc thay đổi port của người dùng. Không cần sửa database/history/migration.
