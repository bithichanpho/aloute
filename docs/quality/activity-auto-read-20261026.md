# Tự đánh dấu thông báo đã đọc khi mở trang

## Audit và phạm vi

Confirmed Bug: `initActivityPage` ban đầu chỉ gọi POST `/api/notifications/read-all` khi bấm nút thủ công. Lát sửa đầu thêm auto-read khi trang khởi tạo nhưng vẫn chờ HTML Activity tải xong mới bắt đầu ghi; thiếu xử lý khi Back khôi phục HTML cũ. Kiểm thử bổ sung tái hiện việc số chưa mất ngay khi bấm trong lúc trang tải chậm. Một GET số chưa đọc chậm cũng có thể khôi phục số cũ sau thao tác ghi.

Already Correct: endpoint lấy người nhận từ principal, repository cập nhật theo receiver bằng một bulk update. Giữ nguyên backend, REST contract, nội dung thông báo và các quyết định yêu cầu theo dõi. GET trang vẫn không ghi database.

## Implemented

- `static/js/app.js`: bắt đầu read-all ngay ở thao tác bấm link Thông báo, ẩn số ngay, dùng chung promise với lúc Activity khởi tạo để không gửi trùng. Mở trực tiếp Activity vẫn auto-read. POST dùng keepalive để có thể tiếp tục nếu rời tài liệu. Khóa nút read-all trong khi xử lý.
- Số badge cập nhật trước khi POST hoàn tất; nếu ghi lỗi, phục hồi số và đọc lại từ server. Chỉ bỏ class unread của các hàng sau khi ghi thành công. Không ghi trạng thái đọc vào bộ nhớ để giả lập đã lưu database.
- Chặn kết quả GET cũ cả khi read-all đang chạy và sau khi commit bằng generation. Đọc lại số từ server sau khi ghi để giữ thông báo mới đến sau commit. Force refresh mỗi lần khởi tạo trang; dùng no-store cho request số badge và refresh khi pageshow.persisted (Back/Forward cache). Badge sidebar và mobile dùng cơ chế đồng bộ sẵn có; không sửa số tin nhắn.
- `templates/layout/base.html`: đổi phiên bản app.js để lấy tài nguyên mới.
- `src/test/browser/activity-read-workflow.cjs` và `activity-navigation-workflow.cjs`: regression với HTTP fixtures và JavaScript thật.

## Tested

- Trước sửa: fixture mở trực tiếp không phát sinh thao tác read-all, regression thất bại như dự kiến.
- Lát sửa bổ sung có test trước sửa thất bại ở kịch bản HTML Activity bị giữ chậm, vì số chưa ẩn ngay khi bấm. Sau sửa cả 3 kịch bản đạt: bấm khi trang tải chậm rồi rời trang; rời trang khi POST vẫn đang chạy và commit sau đó; pageshow.persisted mô phỏng khôi phục trang cũ. Kiểm tra server-rendered badge cũ không trở lại, một POST mỗi lượt vào trang, CSRF và số tin nhắn không đổi.
- 5 kịch bản đọc thông báo đạt — mở trực tiếp/tải lại, soft navigation trên mobile, GET số cũ đến chậm, thông báo mới sau read-all, lỗi POST rồi thử lại. Có kiểm tra nút quyết định follow còn nguyên.
- 13 kịch bản private/follow và 3 nhóm kiểm tra audience/retry cũ đạt. Tổng 24 kịch bản/nhóm trình duyệt trong lượt kiểm tra cuối; không cộng các lần chạy cũ.
- Syntax app.js/browser test và diff whitespace đạt. Maven offline `resources:resources` BUILD SUCCESS.
- HTTP GET asset từ app đang chạy ở 8081 trả 200; SHA-256 bytes trùng source mới. Template trong target/classes trùng source.
- Không khởi động lại backend, không kết nối database để sửa thông báo thật, không migration. Chưa xác nhận thao tác bằng phiên đăng nhập thật của người dùng: Partially Tested về live account; regression dùng fixtures.

## Sử dụng và rollback

Ctrl+F5 một lần để trình duyệt nạp script mới; mở Thông báo sẽ tự cập nhật số. Không cần restart app đang chạy ở workspace này vì asset mới đã được phục vụ.

Rollback chỉ phần auto-read/generation và phiên bản asset của lát cắt này, giữ các thay đổi workspace trước đó. Không có migration ngược hoặc dữ liệu bị xóa; thông báo đã đọc vẫn nằm trong lịch sử.
