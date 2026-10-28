# Chiều cao thẻ Đã lưu — 27/10/2026

Lưu ý: yêu cầu tiếp theo đã thay thế bố cục này bằng bài đăng thông thường, chiều cao tự nhiên và các nút tương tác. Xem saved-feed-20261027.md. Các kết quả dưới đây là bằng chứng của phiên bản chiều cao cố định trước đó.

Đã chỉnh riêng thẻ .feature-saved thành chiều cao cố định 220px, bao gồm padding/border. Nội dung ngắn, dài, nhãn kiểm duyệt, ảnh và video không làm thay đổi chiều cao ngoài của thẻ.

Thêm vùng .feature-saved__body cuộn được bằng chuột/bàn phím, giữ đầy đủ nội dung. Header và nút Bỏ lưu không di chuyển theo vùng cuộn. Thu gọn tên tác giả dài bằng ellipsis để nút không tràn trên điện thoại. Không thay đổi logic lưu/bỏ lưu, API hoặc quyền xem.

File thay đổi: templates/features/index.html, static/css/features.css, templates/layout/base.html (phiên bản CSS saved-card-height-v2).

Kiểm chứng thực chạy:

- FeatureTemplateTest: 10/10 đạt.
- Kiểm tra trình duyệt tạm target/preview-saved-uniform.cjs dùng markup thẻ từ template và CSS/JavaScript thật: 6 cấu hình (1280/390/320px × sáng/tối), mỗi cấu hình 8 dạng bài. Mọi thẻ đo được 220px, không tràn ngang; nội dung dài giữ nguyên và cuộn được; video đổi kích thước vẫn giữ chiều cao thẻ; nút Bỏ lưu giữ vị trí và DELETE đúng endpoint với CSRF.
- Đã xem ảnh desktop và điện thoại. git diff --check đạt; migration không thay đổi.

Browser dùng dữ liệu/HTTP fixture riêng; không sửa dữ liệu thật hoặc khởi động lại backend đang chạy. Có thể cần khởi động lại backend rồi tải lại trang để nạp template/CSS mới. Không chạy lại toàn bộ backend suite vì đây là thay đổi bố cục có thể hoàn tác.
