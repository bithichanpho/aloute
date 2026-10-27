# Xóa người theo dõi — 26/10/2026

## Phạm vi và ownership

Implemented: chủ tài khoản mở Trang cá nhân → Người theo dõi → Xóa. Có xác nhận/Hủy, chống thao tác trùng khi đang xử lý, giữ hàng và cho thử lại nếu lỗi. Cập nhật số lượng ở hồ sơ và tab bằng kết quả backend; tải lại trang đầu sau xóa để offset không bỏ sót hàng. Có Xem thêm theo trang 30 người; truy vấn followers sắp theo id giảm dần cho thứ tự ổn định. Giao diện máy tính/điện thoại dùng bố cục có sẵn.

Not Started/ngoài phạm vi: thay đổi kiến trúc toàn bộ các module, thuật toán feed hay chức năng chặn. Các sửa đổi có sẵn trong workspace được giữ lại.

Social/FriendService sở hữu xóa quan hệ; thêm JPQL DELETE theo đúng followerId → ownerId, không xóa chiều ngược hoặc nội dung người dùng. FollowRequest cũ của đúng cặp được xóa trong cùng giao dịch PostgreSQL. Thao tác idempotent, không dùng toggle để xóa. Không tạo thông báo mới. Không thay đổi close-friend record; quyền close-friends vẫn cần theo dõi hai chiều hiện tại như policy đang có.

## API và bảo vệ

- Mới: DELETE `/api/users/{userId}/followers/{followerId}` → envelope cũ với data `{removed,followerCount}`. Các API cũ giữ nguyên.
- Chủ tài khoản lấy từ principal; userId khác principal trả 403. Không đăng nhập trả 401. CSRF vẫn bắt buộc; service từ chối tự xóa, ID không hợp lệ và owner không còn quyền truy cập.
- Cache quan hệ/suggestion chỉ xóa các khóa bị ảnh hưởng; feed của hai tài khoản được invalidation sau commit. Rollback không chạy invalidation feed.
- Already Correct: backend privacy đọc quan hệ hiện hành. Sau xóa, tài khoản riêng tư chặn timeline/reposts cả API thường và cursor; gửi follow lại tạo yêu cầu mới cần duyệt. Cache feed vẫn phải kiểm tra lại quyền dù invalidation chưa tới. Tài khoản công khai vẫn tuân theo quyền từng bài đang có.
- Không migration, không đổi schema, không chỉnh Redis, không thao tác database người dùng hay khởi động app thật.

## File của tác vụ

- Thêm: dto/response/FollowerRemovalResponse.java; service/FollowerRemovalTest.java; controller/FollowerRemovalControllerTest.java; schema/FollowerRemovalIT.java (ba test nằm trong src/test/java/com/aloute); src/test/browser/remove-follower-workflow.cjs; báo cáo này.
- Sửa trong src/main: controller/FriendController.java; service/FriendService.java; repository/IFriendRepository.java; static/js/app.js; static/css/main.css; templates/layout/base.html.
- Sửa test: service/PrivateProfileContentTest.java; feed/FeedCachedVisibilityTest.java.
- Không xóa file. Các file dùng chung còn chứa thay đổi từ các tác vụ trước; không reset chúng.

## Kết quả thật

- Tested: full `mvn -o -B package` sau thay đổi cuối: BUILD SUCCESS; 488 tests, 0 failures/errors, 1 skipped. Log target/remove-follower-tests-20261026.log. Không tính skipped là pass.
- Tested: verify chọn FollowerRemovalTest/FollowerRemovalControllerTest/PrivateProfileContentTest/FeedCachedVisibilityTest: 30 unit/contract/security tests đạt, và FollowerRemovalIT: 1 integration test đạt trên PostgreSQL 18.6 cục bộ, database ngẫu nhiên riêng. Xác minh JPQL thật, đúng chiều, không ảnh hưởng follower khác, private access, yêu cầu duyệt mới, rollback đồng thời JPA/JDBC, hai lệnh xóa trùng chạy đồng thời chỉ một lệnh trả removed=true. Log target/remove-follower-integration-20261026.log. Chạy JVM test ở UTC do runtime PostgreSQL thử nghiệm không nhận alias Asia/Saigon; không sửa timezone app. Database thử đã được fixture thu hồi và server PostgreSQL thử đã dừng.
- Tested: browser remove-follower-workflow 4 cấu hình đạt, dùng JS/CSS/Bootstrap/UI helpers và markup modal thật với API giả lập: owner desktop/mobile, người khác, chưa đăng nhập; Hủy, lỗi/retry, chống trùng, phân trang 31 người, số lượng, giữ outgoing follow, empty state. Đã xem ảnh điện thoại.
- Tested: private-follow-workflow 13 kịch bản hồi quy đạt; JS syntax và scoped git diff --check đạt.
- Partially Tested: chưa xác nhận bằng tài khoản đăng nhập trên app thật; không chạy load test hoặc nhiều instance. Không tuyên bố kết quả hiệu năng.

## Áp dụng và phục hồi

Chạy lại app để nạp controller/service mới rồi Ctrl+F5. Nếu cần phục hồi, hoàn tác riêng các đoạn của tính năng và asset versions; không reset toàn file chứa sửa đổi trước. Không có migration phải rollback. Quan hệ đã được người dùng chủ động xóa có thể được thiết lập lại bằng luồng follow/duyệt bình thường, không tự động khôi phục hoặc tự chấp nhận.

## Cập nhật dấu × bên cạnh từng người

Theo phản hồi, thay nút chữ Xóa bên dưới nhóm thao tác bằng nút tròn 40px với biểu tượng × ở ngoài cùng bên phải mỗi hàng. Giữ title/aria-label mô tả hành động và luồng xác nhận, CSRF, API cũ. UI nhận diện chủ hồ sơ qua profileUserId và currentUserId, không phụ thuộc thêm cờ data-own-profile; backend vẫn kiểm tra principal như trước.

Tested: remove-follower-workflow.cjs cả 4 cấu hình đạt, gồm chủ hồ sơ trên điện thoại không có cờ own-profile, vị trí nút nằm bên phải và không tràn viewport, SVG/title/aria-label, Hủy/lỗi/thử lại/xóa/phân trang/counters, ẩn nút với người khác và khách. Đã xem ảnh điện thoại. JS syntax và scoped diff check đạt; resources:resources BUILD SUCCESS. Đọc trực tiếp JS/CSS tại localhost:8081 xác nhận trả 200 và đã chứa nút × mới. Không chạy lại Java tests cho thay đổi chỉ JS/CSS này, không ghi dữ liệu app thật. Ctrl+F5 rồi mở lại danh sách để nạp giao diện.
