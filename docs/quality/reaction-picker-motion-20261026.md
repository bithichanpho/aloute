# Thanh cảm xúc — 26/10/2026

Implemented: áp dụng kiểu thanh bo tròn theo mẫu Uiverse.io của Mayurwaghgpr bằng CSS hiện có. Giữ sáu reaction và API hiện hành. Nền sáng/tối, nút tròn, hover nâng/phóng to, nhãn tiếng Việt, viền lựa chọn và hiệu ứng sau chọn. Không thêm Tailwind, framework hay loại reaction mới.

Confirmed Bug: `.reaction:focus-within` khiến picker còn hiển thị sau khi bỏ class mở. Nay trạng thái mở do JS điều khiển; picker đóng có inert/aria-hidden, trigger có aria-expanded. Chuyển visibility sang tức thời khi mở và trì hoãn khi đóng để mở lại nhanh bằng bàn phím vẫn nhận focus đúng.

Already Correct: hàng đợi optimistic reaction, retry/rollback, POST type, CSRF và long press 450ms. Giữ nguyên nghiệp vụ. Thêm ArrowUp/Down mở menu, Left/Right/Home/End chọn vị trí, Escape đóng, trả focus sau chọn bằng bàn phím. Thanh tự căn trong viewport và mở phía dưới khi nút sát đầu màn hình. Tôn trọng prefers-reduced-motion.

File sửa: static/css/main.css, static/js/app.js, templates/layout/base.html (phiên bản JS/CSS). File mới: src/test/browser/reaction-picker-workflow.cjs và báo cáo này. Không sửa Java/database/migration/Redis.

Tested:

- Browser fixture dùng nút từ template feed thật, app.js/main.css/theme.css thật, API giả lập. 7 cấu hình đạt: desktop sáng/tối ở 1280/390px; cảm ứng 320/390px với nhấn giữ thật qua touch events; reduced motion. Kiểm tra nhãn, hover, đóng sau chọn, vị trí viewport, keyboard, API lỗi/rollback/retry, đổi liên tục khi request chờ, CSRF và chạm bỏ reaction. Đã xem ảnh sáng desktop và tối điện thoại.
- Hồi quy: activity-read 5, activity-navigation 3, compose-audience 16, post-audience 3; tổng 27 kịch bản đạt.
- `node --check src/main/resources/static/js/app.js` và scoped `git diff --check`: đạt.
- `mvn -o -B resources:resources`: BUILD SUCCESS; đã chép tài nguyên vào target/classes. Log target/reaction-picker-resources-20261026.log. Không chạy lại Java tests vì không thay đổi backend; không coi lệnh resources là full package/test.

Partially Tested: chưa kiểm tra thao tác bằng tài khoản đăng nhập trên app thật; không chạy hoặc cập nhật database dùng chung. Không tự khởi động app.

Áp dụng: Ctrl+F5 để nạp JS/CSS mới. Rollback chỉ các đoạn picker và phiên bản assets của thay đổi này, giữ những sửa đổi có sẵn trong cùng file. Không cần khôi phục dữ liệu.
