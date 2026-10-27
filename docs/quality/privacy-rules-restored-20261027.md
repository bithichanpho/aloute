# Áp lại luật riêng tư — 27/10/2026

## Phạm vi đã xác nhận

Áp lại bộ luật riêng tư trong nhánh được giữ `codex/current-code-20261027`, có khôi phục quyền bài Bạn thân theo xác nhận của người dùng. Làm chọn lọc trên develop-ptrang; giữ toàn bộ sửa đăng ký, follow/chặn, ChatMessage, Docker và port đã có. Không sao chép toàn bộ nhánh cũ.

## Audit

| Phân loại | Bằng chứng / quyết định |
|---|---|
| Already Correct | canViewAccountContent và bốn truy vấn profile timeline/repost list/cursor đã chặn private outsider/pending/removed follower. Giữ API và implementation, không đổi tên chỉ để giống bản cũ. |
| Confirmed Bug | Template profile vẫn render tabs/list/grid/repost/loaders dù viewer chưa có quyền; follow mutation không loại DOM đã tải của profile private. Bổ sung guard HTML và reload sau thay đổi follow. |
| Confirmed gap | CLOSE_FRIENDS đã bị bỏ khỏi enum/edit command/composer và không có provider/policy. Khôi phục dựa trên bảng close_friends, không thêm persistence khác. |
| Already Correct | ChatService, ChatController và ConversationService hiện có gates canMessageAccount/messageableProfileIds mạnh hơn bản cũ; giữ nguyên, diff các file này với HEAD rỗng. |
| Confirmed Bug | NotificationApplicationService dùng immutable Set.contains(null), đã gây lỗi test đọc notification. Sửa null guard trong phần đọc đang thay đổi để khôi phục close-friend notifications. |
| Requires Integration Testing | JPQL acceptPending, transaction và race trên PostgreSQL thật; session hai tài khoản, STOMP/FCM thật. Chưa chạy trong lượt này. |

## Luật áp dụng

| Trường hợp | Quyền |
|---|---|
| Chủ tài khoản | Xem nội dung của mình theo các gates hiện có. Quyền bài gốc vẫn áp dụng cho repost. |
| Hồ sơ private | Chỉ chủ và follower được chấp nhận hiện tại được đọc nội dung; không dựa vào lịch sử follow hoặc pending request. |
| Outsider/pending/follower đã bị gỡ | Không có timeline/ảnh/repost/cursor nội dung; HTML chỉ hiện thông báo private, không có loaders hay dữ liệu dù model vô tình chứa posts. |
| FRIENDS | Cần theo dõi hai chiều; vẫn phải qua active/block/community/moderation gates. |
| CLOSE_FRIENDS | Cần quan hệ ACCEPTED ở một trong hai chiều và follow hai chiều hiện tại; PENDING không cấp quyền. Xóa Bạn thân/mất follow thu hồi quyền trên lần đọc mới, kể cả cache hit chưa được evict. |
| ONLY_ME | Chỉ tác giả; không cấp quyền cho follower hoặc Bạn thân. |
| Block/inactive/group/moderation | Giữ gates backend hiện tại; không copy các phần chat yếu hơn từ nhánh cũ. |

## Implemented / ownership

- Social: CloseFriendships public port và JpaCloseFriendships projection theo lô tối đa 500 ID, chỉ đọc quan hệ ACCEPTED thuộc viewer/candidate thực tế.
- ContentAccessService: thêm CLOSE_FRIENDS vào scalar/batch; dùng public port. Giữ các constructor cũ với mặc định không cấp quyền Bạn thân khi thiếu port.
- CloseFriendService/Controller: khôi phục request/accept/delete/list trên bảng cũ. Actor lấy từ principal; request/accept yêu cầu profile active/không block và mutual follow. Private list cần quyền đọc nội dung; cho phép xóa quan hệ của chính mình kể cả đã block để thu hồi quyền.
- acceptPending là JPQL conditional update PENDING -> ACCEPTED đúng chiều requester/recipient; chỉ bên cập nhật được row enqueue acceptance notice. Retry sau bên thắng trả ACCEPTED mà không notify lần nữa. Không bổ sung cột/version/migration. Race test trong lượt này là storage double, không phải giao dịch PostgreSQL thật.
- UserService/UserProfileDto: thêm closeFriendStatus cho profile được phép xem, query batch một profile; giữ constructor tương thích cho callers/tests hiện tại.
- Notifications: chỉ FOLLOW_REQUEST còn bị lọc theo luồng hiện tại; CLOSE_FRIEND_REQUEST/ACCEPTED hiển thị lại và đi qua outbox cũ. Notification nhấp được sang profile để duyệt. Không xây pipeline mới hoặc tuyên bố exactly-once.
- UI: ẩn nội dung private, reload sau follow/close-friend mutation; profile có gửi/hủy/xóa Bạn thân và Chấp nhận/Từ chối lời mời nhận vào. Composer/edit có bốn audience, dùng select/layout hiện tại. Biểu tượng Bạn thân chỉ hiện với trạng thái kiểm duyệt APPROVED, giữ nguyên nhãn PENDING/FLAGGED/HIDDEN hiện có.

## Files của lượt này

Mới/khôi phục: social/api/CloseFriendships.java, social/infrastructure/JpaCloseFriendships.java, service/CloseFriendService.java, controller/CloseFriendController.java; PrivateProfileTemplateTest, PrivateProfileContentTest, CloseFriendPostVisibilityTest, CloseFriendshipsTest, CloseFriendPrivacyTest, CloseFriendPrivacyControllerTest; browser/close-friend-privacy.cjs và báo cáo này.

Sửa: ContentAccessService, UserService, UserProfileDto, PostVisibility, PostEditCommand, ICloseFriendRepository, NotificationApplicationService; templates/profile/view.html, feed/index.html, fragments/post-composer.html, layout/base.html; static/js/app.js, features.js. Các file này có thể còn chứa sửa đổi từ lượt trước và không được reset toàn file khi rollback.

Tests mở rộng: FeatureTemplateTest, FeedCachedVisibilityTest, FeedQueryBudgetTest, NotificationApplicationServiceTest, browser/private-follow-workflow.cjs. Fixture FeedQueryBudget cấp canViewAccountContent cho owner đúng mục đích test query budget; không đổi production gate. Test lọc notification đổi từ CloseFriend sang FOLLOW_REQUEST vì người dùng chủ đích khôi phục CloseFriend. Browser fixture public-follow trả FOLLOWING theo trạng thái backend sau thao tác, thay vì trả NONE trái với ý nghĩa kịch bản.

## Tested — kết quả thực tế

- Baseline: 44 tests, 43 đạt, 1 failure do fixture FeedQueryBudgetTest thiếu quyền profile gate (đã có trước lượt này).
- Gate đầu: 85 tests đạt. Gate mở rộng sau CAS: BUILD SUCCESS, 103 tests đạt, không failure/error/skip.
- Sau bổ sung UI Chấp nhận/Từ chối: FeatureTemplateTest, PrivateProfileTemplateTest, CloseFriendPrivacyTest, CloseFriendPrivacyControllerTest đạt 21 tests, không failure/error/skip. Có thêm một test mới; không cộng lượt này vào 103 để tránh đếm trùng.
- Architecture: 12 tests đã chạy; 10 đạt, 2 lỗi cũ ở Notifications.notifyPostModerationResult phụ thuộc trực tiếp model.document.ModerationStatus. Không suppress hoặc sửa tests để bỏ qua; không tuyên bố toàn bộ build đạt.
- Browser private-follow-workflow: 16 kịch bản đạt (13 follow/list/realtime trước đó + 3 private-unfollow/private-pending/public-follow).
- Browser close-friend-privacy: 14 kịch bản đạt (6 gửi/duyệt/xóa có reload và loại nội dung cũ + 8 composer audience/payload/CSRF trên 1280/390px). HTML composer được render bằng Thymeleaf, JS/CSS thật, API fixtures không ghi tài khoản thật.
- JavaScript syntax và git diff --check đạt. Migration diff với HEAD rỗng; ChatService/ChatController/ConversationService diff rỗng.

Lệnh gate mở rộng:

```powershell
mvn --batch-mode '-Dtest=PrivateProfileTemplateTest,PrivateProfileContentTest,CloseFriendPostVisibilityTest,CloseFriendshipsTest,CloseFriendPrivacyTest,CloseFriendPrivacyControllerTest,ContentAccessServiceTest,ContentAccessBatchTest,FeedCachedVisibilityTest,FeedQueryBudgetTest,PostControllerWebTest,UserProfileQueryBudgetTest,FeatureTemplateTest,NotificationApplicationServiceTest,PostEditingServiceTest' test
```

Chưa chạy PostgreSQL integration/JPQL/concurrency thật, live accounts, STOMP/FCM, load/EXPLAIN; không kết nối database người dùng hoặc tự chạy Flyway. Trạng thái: Implemented / Partially Tested. Không kết luận hoàn tất toàn bộ master refactor.

## Áp dụng và rollback

Khởi động lại backend rồi Ctrl+F5. Mọi instance dùng chung dữ liệu cần hiểu enum CLOSE_FRIENDS trước khi dùng quyền này. Không cần migration/schema change. Khi đã lưu bài CLOSE_FRIENDS, rollback UI bằng cách ẩn lựa chọn nhưng giữ backend đọc được enum và policy; không đổi bài sang PUBLIC hoặc rollback về binary không hiểu enum.

Chỉ rollback chọn lọc nhóm policy/provider/management/UI của lượt này; giữ các sửa đăng ký/follow/chặn/Docker/ChatMessage và dữ liệu thật. Quyền bị thu hồi không thể xóa nội dung đã tải về mọi thiết bị; backend kiểm tra quyền ở các lần đọc mới, client hiện tại chủ động reload khi thực hiện mutation.
