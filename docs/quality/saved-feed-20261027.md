# Đã lưu hiển thị như bài đăng bảng tin — 27/10/2026

Trạng thái: Implemented / Partially Tested.

## Hành vi và audit

- Theo yêu cầu mới, thay thẻ cao cố định bằng bài đăng như bảng tin: avatar, tác giả/link hồ sơ, thời gian, nhãn kiểm duyệt/quyền xem, nội dung, ảnh/video, trích dẫn và các nút cảm xúc/bình luận/đăng lại/chia sẻ/bookmark.
- Dùng lại chính markup bảng tin bằng fragment postCards(posts, savedPage) khai báo trên root .post hiện hữu. Không sao chép thêm một template bài đăng khác. Giữ .post và vòng lặp để endpoint feed/index :: .post tiếp tục trả đúng fragment; kiểm tra hồi quy này đã chạy.
- Bỏ vùng cuộn bên trong và CSS cao 220px của phiên bản trước. Chiều cao nội dung tự nhiên, cột rộng tối đa 780px, tiêu đề gọn.
- Bookmark trong trang Đã lưu bắt đầu với trạng thái đã lưu. Bỏ lưu thành công mới gỡ bài khỏi DOM; lỗi giữ nguyên bài và cho thử lại. Bỏ bài cuối hiện empty state.
- Hủy lưu trích dẫn dùng ID bài đã lưu; cảm xúc/bình luận/đăng lại vẫn dùng ID bài gốc qua data-action-post-id. Không đổi các API hoặc quy tắc backend.
- Bổ sung CSS chỉ cho saved-stream trên mobile: popup emoji và hàng nút vừa màn hình; metadata xuống dòng, nút ba chấm giữ góc trên.
- Backend SavedPostsService/PostCards đã dùng lắp thẻ theo batch và quyền viewer hiện có: giữ nguyên, không thêm truy vấn theo từng bài.

## File thay đổi

- templates/feed/index.html: khai báo fragment và marker saved ID tùy ngữ cảnh; không di chuyển hoặc thay nội dung bài.
- templates/features/index.html: gọi fragment dùng chung cho danh sách đã lưu.
- static/css/features.css: bố cục và mobile scoped; loại CSS chiều cao cố định.
- static/js/features.js: bookmark ban đầu, đúng ID khi bỏ lưu, chỉ xóa DOM sau thành công và empty state.
- templates/layout/base.html: phiên bản CSS/JS mới.
- FeatureTemplateTest.java và src/test/browser/saved-feed-workflow.cjs: kiểm tra template/selector/action/ID/escaping và thao tác giao diện.

## Kiểm chứng thực chạy

```powershell
mvn --batch-mode '-Dtest=FeatureTemplateTest,PrivateProfileTemplateTest,PrivateProfileContentTest,PostControllerWebTest' test
```

36/36 đạt. Bao gồm template Đã lưu có thẻ thường/ảnh/video/trích dẫn/bị đánh dấu, giữ escape nội dung, selector fragment bảng tin, quyền private và HTTP cảm xúc hiện có.

saved-feed-workflow.cjs: 6/6 workflow đạt, 1280/390/320px × sáng/tối. Dùng HTML Thymeleaf render thật cùng CSS/JS thật, HTTP fixture riêng. Kiểm tra chiều cao tự nhiên theo media, không tràn ngang, bookmark đã chọn, thả/hủy cảm xúc, gửi bình luận, đăng lại, mở chia sẻ, lỗi 403 khi bỏ lưu và thử lại không gửi DELETE trùng, đúng ID trích dẫn, gỡ bài và empty state. Có kiểm tra CSRF header.

Đã xem ảnh desktop tối và mobile sáng. Video fixture không phát nội dung thực; chưa xác minh playback từ storage thật hoặc chia sẻ tới người nhận thật. Không dùng database người dùng cho kiểm tra, không sửa Java business code/schema/migration, không push/reset/rollback hoặc khởi động lại backend.

Cần khởi động lại backend và tải lại trang để nạp template/asset mới. Phục hồi nếu cần phải hoàn tác riêng phần fragment/saved marker/bookmark/CSS, giữ các thay đổi khác trong cùng file; không có migration cần phục hồi.
