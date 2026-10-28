# Nút sáng/tối đơn sắc — 27/10/2026

Đã áp mẫu Uiverse.io của Creatlydev với hiệu ứng xoay và thu/phóng 500ms, trễ 200ms. Theo phản hồi tiếp theo, đã bỏ nền tròn và bóng đổ, thu icon về kích thước cũ; icon dùng màu chữ hiện tại để rõ trên cả giao diện sáng/tối. Có ghi nguồn trong CSS.

Thay đổi giới hạn ở theme.css, fragment theme-switch mới, navbar, sáu trang auth và phiên bản URL theme.css trong layout/auth. Dùng tên class riêng để không ảnh hưởng các toggle/input/icon khác. Giữ nút button hiện có và cơ chế theme-init.js/ui.js; không thêm checkbox ẩn hoặc handler thứ hai.

Giữ localStorage, đồng bộ giữa các tab, soft navigation và phím Space/Enter. Icon 22px, mobile 20px; vùng icon 24px, vùng bấm auth 42px như trước. Tắt chuyển động và độ trễ khi người dùng chọn giảm chuyển động. Phiên bản tài nguyên hiện tại: moon-sun-switch-v3.

Sau phản hồi về chữ và hình ngược nhau, icon được đồng nhất với chế độ đích: khi đang tối hiển thị mặt trời và chữ Giao diện sáng; khi đang sáng hiển thị mặt trăng và chữ Giao diện tối. Chỉ đổi điều kiện CSS; logic chuyển/lưu chế độ không thay đổi.

Kiểm chứng:

- `mvn --batch-mode '-Dtest=FeatureTemplateTest,GlobalThemeTemplateTest,AuthTemplateCspTest' test`: 12/12 đạt.
- Kiểm tra trình duyệt bằng tài nguyên thật và fixture riêng: shell/auth ở 1280/900/390px đều đạt với màu đơn sắc, hiệu ứng, click, Space/Enter, tải lại và đồng bộ tab; thêm kiểm tra giảm chuyển động đạt.
- Đã xem ảnh nút mặt trăng và mặt trời; không phụ thuộc font icon để vẽ hai biểu tượng này.
- git diff --check và cú pháp ui.js đạt. Không sửa Java backend, database/migration hoặc nội dung form. Chưa kiểm tra trên mọi trình duyệt thật.

Khởi động lại backend và tải lại trang để nạp template/tài nguyên mới. Không rollback/reset các sửa trước đó.
