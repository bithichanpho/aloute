# Chọn đối tượng khi đăng bài và quyền Bạn thân — 26/10/2026

## Phạm vi đã xác nhận

Người dùng yêu cầu giao diện chọn quyền xem giống ảnh tham khảo và xác nhận chỉ thêm **Bạn thân** vào ba quyền đang có. Kết quả gồm PUBLIC, FRIENDS, CLOSE_FRIENDS, ONLY_ME. Không thêm chọn người cụ thể hoặc danh sách loại trừ.

| Phân loại | Bằng chứng hiện trạng | Hành động |
|---|---|---|
| Confirmed gap | Composer dùng select ba giá trị; enum/command chưa có CLOSE_FRIENDS | Thêm hộp chọn bốn hàng và giá trị mới ở backend |
| Already Correct | close_friends đã có PENDING/ACCEPTED, quan hệ hai chiều, index requester/receiver; quản lý bạn thân đã tồn tại | Dùng quan hệ hiện tại, không tạo bảng hoặc danh sách khác |
| Security requirement | AccessBatch/scalar trước đây xử lý PUBLIC/FRIENDS/ONLY_ME | Thêm nhánh CLOSE_FRIENDS rõ ràng trước nhánh mặc định, không để quyền mới được hiểu thành PUBLIC |
| Already Correct | Feed cache được đọc lại và kiểm tra quyền hiện tại qua PostFeedReader; profile/comment/repost/notification dùng policy backend | Giữ đường kiểm tra quyền và thêm regression cho việc hủy bạn thân trên cache hit |

## Implemented

| Thành phần | Owner và thay đổi | Trạng thái |
|---|---|---|
| Social | Public port `CloseFriendships.acceptedAmong`, provider JPA dùng repository close_friends hiện có | Implemented / Tested |
| Posts/visibility | CLOSE_FRIENDS trong enum/create/edit; scalar và batch policy; metadata ngôi sao ở bài gốc, quote, feed/profile/search/saved | Implemented / Tested |
| Composer | Dialog bốn hàng có biểu tượng, mô tả, radio xanh, nút Xong; dùng chung ở feed và trang đăng bài | Implemented / Tested |
| Compatibility | Giữ constructor policy/AccessBatch cũ cho callers; constructor cũ không có port luôn từ chối quyền Bạn thân của người khác | Implemented / Tested |

**Quy tắc:** chủ bài vẫn đọc được bài của mình theo policy hiện có. Người khác cần quan hệ bạn thân ACCEPTED ở một trong hai chiều **và** đang theo dõi lẫn nhau. PENDING/quan hệ đã xóa, bỏ theo dõi, chặn, tài khoản bị khóa, bài chưa duyệt/bị ẩn, hoặc quyền cộng đồng không phù hợp đều không được đọc. Bạn thân không vượt qua private profile hoặc quy tắc duyệt bài. Repost không được công khai hóa bài gốc Bạn thân.

Port mới chỉ trả immutable IDs; không đưa entity/repository Social vào module API. Batch chỉ gọi port khi có bài CLOSE_FRIENDS, gồm cả bài gốc của repost, và giới hạn query 500 author IDs. Không tải toàn bộ danh sách bạn thân. Query-count test xác nhận 120 thẻ được kiểm tra bằng một lookup, không có lookup thêm khi đọc lại AccessBatch; batch toàn PUBLIC không query bạn thân. Không tuyên bố cải thiện độ trễ khi chưa benchmark.

Giao diện hỗ trợ native modal/focus trap, radio bằng bàn phím, Escape/Đóng hủy lựa chọn chưa xác nhận; Xong cập nhật giá trị gửi lên API, không đăng bài. Có mô tả riêng về giới hạn tài khoản riêng tư. Chỉnh sửa bài giữ được giá trị CLOSE_FRIENDS; chỉ đổi audience không tự duyệt lại nội dung.

## Files

Mới:

- `src/main/java/com/aloute/social/api/CloseFriendships.java`
- `src/main/java/com/aloute/social/infrastructure/JpaCloseFriendships.java`
- Tests `social/CloseFriendshipsTest.java`, `service/CloseFriendPostVisibilityTest.java`, `schema/CloseFriendAudienceIT.java`, `src/test/browser/compose-audience-workflow.cjs`.

Sửa:

- `model/document/PostVisibility.java`, `posts/api/PostEditCommand.java`, `service/ContentAccessService.java`.
- Templates `fragments/post-composer.html`, `fragments/post-metadata.html`, `layout/base.html`.
- Assets `static/css/main.css`, `static/js/app.js`, `static/js/features.js`.
- Tests `FeatureTemplateTest`, `PostMetadataTemplateTest`, `feed/FeedCachedVisibilityTest`, `controller/PostControllerWebTest`, `posts/PostEditingServiceTest`, `browser/post-audience-workflow.cjs`.

Không xóa file, sửa migration, đổi tên trường JSONB/table/collection, đổi ranking hay thêm broker/storage. Các thay đổi thông báo/private/migration đã có trong workspace được giữ lại.

## Test evidence

- Lượt focused đầu: 74 tests, không failure/error. Có unit/security, query count, architecture, template và cache revocation.
- Lượt full unit: **472 tests, 471 đạt, 1 bỏ qua** (application context cần DB_URL). Log `target/close-friend-audience-verify-20261026.log`. Không cộng lại các lượt focused.
- Integration đầu phát hiện fixture test thiếu role_id bắt buộc khi tạo user; đã bổ sung ROLE_USER cho dữ liệu thử, không sửa schema/constraint để làm test pass. Unit trong lượt đó vẫn đạt; không báo toàn lượt verify ban đầu là thành công.
- Rerun corrected verify: **BUILD SUCCESS**, 19 unit liên quan và **1 integration PostgreSQL 18.6 đạt**, không skip/failure/error. Log `target/close-friend-audience-integration-20261026.log`.
- Integration dùng database local ngẫu nhiên do `DisposablePostgres` sở hữu, chạy migrations rồi kiểm tra JPA query thật và JSONB round trip. Kiểm tra PENDING không cho đọc, ACCEPTED cho đọc, xóa bạn thân thu hồi, ACCEPTED chiều ngược vẫn hợp lệ, bỏ follow thu hồi, chủ bài đọc được và anonymous không đọc được. Database thử được hủy; test server riêng được dừng sau đó. Không sử dụng credentials hoặc database Render cho các tests.
- Browser: **16 luồng đăng bài** (4 audience × desktop/mobile × dark/light) đạt. Có kiểm tra payload/CSRF, hủy bằng Escape, keyboard radio, giữ lựa chọn khi mở lại, chọn audience không tự submit. Dùng HTML được Thymeleaf render thật và assets ứng dụng, API fixtures không ghi bài thật. Đã xem ảnh popup dark/light mobile; popup không tràn màn hình.
- Chạy lại **24 kịch bản/nhóm browser regression**: thông báo/điều hướng, follow/private và metadata/retry. Logs `target/close-friend-*-20261026.log`.
- JS syntax và scoped diff whitespace đạt. Chưa chạy bằng phiên đăng nhập thật của người dùng hoặc upload media thật trong popup test: Partially Tested ở tích hợp live; không tuyên bố đã tạo bài Bạn thân trên dữ liệu thật.

## Chạy và tương thích

**Cần chạy lại backend** để nạp enum, public port/provider và policy mới; sau đó Ctrl+F5 để lấy JS/CSS mới. Khác với lát sửa badge chỉ có frontend, tính năng này có thay đổi Java. Không tự restart app trỏ database Render trong phiên sửa mã.

REST giữ URL/method/status/shape cũ, bổ sung giá trị enum `CLOSE_FRIENDS`; lưu trong trường visibility JSONB đã có nên không cần migration. Các quyền cũ và dữ liệu cũ giữ nguyên. Không thay đổi STOMP/WebRTC contracts.

Nếu nhiều máy/instance cùng dùng database, nâng tất cả backend lên bản hiểu CLOSE_FRIENDS trước khi dùng quyền mới. Khi đã có bài mang giá trị này, không rollback về binary cũ không biết enum; rollback UI bằng cách ẩn lựa chọn mới trong khi giữ backend đọc được CLOSE_FRIENDS. Không đổi bài Bạn thân thành PUBLIC để rollback và không xóa dữ liệu.
