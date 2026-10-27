# Hiển thị danh sách tài khoản chặn — 26/10/2026

## Phạm vi và kết quả

Implemented: danh sách chặn dùng cùng bố cục với yêu cầu theo dõi: avatar tròn, tên hiển thị, @username, nút Bỏ chặn. Cả HTML ban đầu và các trang cursor có cùng dữ liệu. Ảnh hỏng dùng ảnh mặc định; tên không được diễn giải thành HTML; tài khoản thiếu hồ sơ có nhãn thay thế. Bỏ chặn thành công mới xóa hàng; lỗi giữ hàng và cho thử lại; danh sách hết hàng hiển thị trạng thái trống.

Confirmed Bug: trước thay đổi, danh sách chặn chỉ có targetId và hiển thị “Tài khoản #...”. Already Correct: cursor theo owner, kiểm tra quyền ở backend, cập nhật block và avatar fallback dùng chung. Không thay đổi các quy tắc này.

## Ownership, contract và dữ liệu

- SocialFeatureService vẫn sở hữu truy vấn block, lấy nhãn công khai theo lô qua users.api.UserDirectory, chỉ cho các target thuộc trang trả về. Không truy vấn từng hàng, không lấy toàn bảng để phân trang.
- DTO BlockedAccountResponse giữ id/ownerId/targetId/createdAt và thêm targetUsername/targetFullName/targetAvatarUrl. Không trả relationshipsSynced, mật khẩu hay token. Không thêm trường hiển thị vào entity lưu trữ.
- URL/method API, principal owner, cursor và phân quyền chặn giữ nguyên. Bấm tên khi đang chặn hiển thị lời nhắc bỏ chặn trước; không tự bỏ chặn hay mở quyền xem hồ sơ.
- Không migration, không đổi dữ liệu hay cấu hình Redis, không khởi động backend kết nối database dùng chung.

## File của thay đổi này

- Mới: src/main/java/com/aloute/dto/response/BlockedAccountResponse.java; src/test/browser/blocked-accounts-workflow.cjs; báo cáo này.
- Sửa: SocialFeatureService.java; templates/features/index.html; static/js/features.js; templates/layout/base.html (phiên bản features.js); FeatureTemplateTest.java; SocialFeatureServiceTest.java.
- Các file trên còn có thay đổi từ các tác vụ trước; không reset hoặc ghi đè chúng. Không xóa file.

## Kiểm chứng thực tế

- Tested: `mvn -o -B package` với giới hạn heap JVM; BUILD SUCCESS. 476 tests, 0 failures, 0 errors, 1 skipped. Log: target/blocked-accounts-tests-20261026.log. Không tính test skipped là pass.
- Tested: blocked-accounts-workflow.cjs, 4 kịch bản ở 1280/390px và sáng/tối: SSR identity, tên chứa HTML, cursor/dedupe, avatar lỗi, nhắc bỏ chặn, DELETE có CSRF, lỗi/thử lại, trạng thái trống. Dùng HTML Thymeleaf thật, JS/CSS thật, API giả lập độc lập.
- Tested: private-follow-workflow.cjs, 13 kịch bản hồi quy đạt. JavaScript syntax và scoped git diff --check đạt.
- Partially Tested: đã xem ảnh chụp giao diện và chạy browser fixture; chưa xác nhận trực tiếp bằng hai tài khoản đăng nhập trên app đang chạy. Không chạy integration test database thật cho thay đổi hiển thị này.

## Áp dụng và phục hồi

Chạy lại app để nạp DTO/service mới, rồi Ctrl+F5 trang Cài đặt. Nếu cần phục hồi, chỉ hoàn tác phần block DTO/service/template/JS của tác vụ này cùng nhau, không reset các sửa đổi trước. Không cần thao tác dữ liệu hay migration khi phục hồi.
