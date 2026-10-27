# Icon lựa chọn bài đăng và cách đếm bài — 27/10/2026

Trạng thái: Implemented / Partially Tested. Thực hiện chọn lọc trên develop-ptrang, bảo toàn các thay đổi đang có.

## Hành vi

- Theo phản hồi tiếp theo của người dùng, hộp chọn đã đổi thành menu nhỏ xổ ngay dưới nút, có icon cho Công khai, Bạn bè, Bạn thân và Chỉ mình tôi. Bấm lựa chọn áp dụng ngay; bàn phím hỗ trợ mũi tên/Enter, Escape hoặc bấm ngoài đóng menu. Không còn dialog lớn và nút Xong.
- Tổng bài trên hồ sơ tính mọi đối tượng xem, kể cả dữ liệu cũ có visibility null. Giữ điều kiện chủ bài, loại HIDDEN, tính bài gốc và đăng lại có lời dẫn, không tính đăng lại thuần.
- Tổng không phụ thuộc số bài tải trên trang hoặc quyền đọc nội dung của người xem. Tổng không cấp quyền xem bài; các kiểm tra private/block/Bạn thân vẫn giữ nguyên.
- Dùng một truy vấn SQL count, không tải toàn bộ bài để đếm. Không thay schema, migration, API đăng bài hoặc dữ liệu.

## File chính

- templates/fragments/post-composer.html, static/css/main.css, static/js/app.js: hộp chọn icon và tương tác.
- templates/layout/base.html: phiên bản tài nguyên để trình duyệt tải thay đổi.
- service/PostService.java, controller/ViewController.java: truy vấn tổng và hiển thị tổng hồ sơ.
- ProfilePostCountTest, FeedQueryBudgetTest, FeatureTemplateTest, ProfilePostCountIT: kiểm chứng tổng, một truy vấn và template.
- src/test/browser/compose-audience-workflow.cjs, close-friend-privacy.cjs: kiểm chứng hộp chọn, payload và riêng tư.

## Kết quả thực chạy

Hai kiểm tra mới đã thất bại trước bản sửa: tổng hồ sơ outsider trả 0 thay vì 105; truy vấn tổng vẫn lọc PUBLIC.

Lệnh sau bản sửa:

```powershell
mvn --batch-mode '-Dtest=ProfilePostCountTest,FeedQueryBudgetTest,FeatureTemplateTest,PrivateProfileTemplateTest,PrivateProfileContentTest,CloseFriendPostVisibilityTest,FeedCachedVisibilityTest,PostControllerWebTest' '-Dit.test=ProfilePostCountIT' verify
```

- BUILD SUCCESS: 45 kiểm tra Java đạt, không lỗi. Đây là tập kiểm tra liên quan, không phải toàn bộ suite.
- ProfilePostCountIT: 1 skipped do chưa cấu hình PostgreSQL thử riêng. Chưa xác minh trên PostgreSQL thật và chưa chạy ứng dụng với dữ liệu thực.
- compose-audience-workflow.cjs: 16 kịch bản đạt, desktop/mobile và sáng/tối, bốn đối tượng, Escape, bàn phím, payload đăng bài.
- close-friend-privacy.cjs: 14 kịch bản đạt.
- Kiểm tra cú pháp JavaScript và git diff --check đạt; migration không có thay đổi.
- Đã xem ảnh mobile tối: icon, nhãn, radio và nút xác nhận hiển thị đúng, không tràn ngang.

Các lỗi architecture notification/moderation đã ghi trong báo cáo riêng vẫn chưa được xử lý trong phạm vi này. Không báo toàn bộ dự án đạt.

## Phục hồi

Nếu cần phục hồi riêng thay đổi này, hoàn tác từng phần hộp chọn/CSS/JavaScript và hai phần đếm trên Java, cập nhật phiên bản tài nguyên rồi build lại. Không reset toàn bộ file vì chúng chứa các sửa khác của phiên làm việc. Không cần migration hay phục hồi dữ liệu.

Đây chỉ là hướng dẫn phục hồi khi cần; không có lệnh rollback/reset nào được thực thi trong các thay đổi này.

## Kiểm chứng sau phản hồi về menu và yêu cầu theo dõi

- Bỏ danh sách activityFollowRequests khỏi ViewController.activity và template Hoạt động. Trang /follow-requests, badge, trang cài đặt và quyết định trên hồ sơ vẫn giữ nguyên. FOLLOW_REQUEST vẫn bị chặn ở notification enqueue/read/delivery theo implementation sẵn có; không xóa dữ liệu thông báo.
- Sửa bài kiểm tra template theo hành vi người dùng yêu cầu; bổ sung ActivityPageTest bảo đảm trang Hoạt động không tải danh sách yêu cầu nhưng vẫn có số đếm của mục riêng.
- FeatureTemplateTest, ProfilePostCountTest, NotificationApplicationServiceTest: 27/27 đạt. Chạy thêm FeatureTemplateTest và ActivityPageTest: 11/11 đạt (10 bài template trùng lượt trước; tổng 28 bài Java khác nhau).
- compose-audience-workflow: 16/16 đạt; close-friend-privacy: 14/14 đạt; private-follow-workflow: 16/16 đạt. Fixture theo dõi được chuyển từ surface activity sang trang follow-requests để phản ánh UI thực tế.
- Đã xem ảnh dropdown mobile tối: menu gọn nằm dưới nút, đủ bốn icon và không tràn màn hình. Kiểm tra JavaScript và diff whitespace đạt.
- Chưa chạy ứng dụng với PostgreSQL/STOMP thật. Cách đếm bài và migration không thay đổi trong lần chỉnh menu này.
