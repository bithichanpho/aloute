# Kiểm tra đăng ký cùng lượt — 27/10/2026

## Yêu cầu và kết quả

Người dùng xác nhận kiểm tra tên bị trùng cùng các lỗi khác khi bấm Đăng ký, không kiểm tra nền khi đang nhập. Bỏ khung thông báo tên trùng nằm giữa hộp tổng hợp và các trường; giữ viền đỏ và lỗi tại ô tên đăng nhập.

Frontend kiểm tra định dạng các trường, rồi kiểm tra tên hợp lệ bằng request chỉ đọc ngay cả khi email/mật khẩu còn sai. Tổng hợp các lỗi trong cùng lượt submit. Không gọi bước đăng ký khi còn lỗi. Khóa form trong lúc chờ, không gửi lặp khi bấm liên tiếp; khôi phục khi lỗi mạng/rate limit. Lỗi chung như mạng/429 vẫn có khung thông báo chung.

## Backend và contract

- Thêm `POST /api/auth/register/username-availability`: body gồm `username` và `email` tùy chọn; response giữ ApiResponse envelope, data chỉ có boolean `available`, Cache-Control no-store.
- AuthController kiểm tra format/độ dài username, độ dài email và rate limit 60 lần/15 phút theo IP qua cơ chế hiện có.
- AuthService đọc username không phân biệt hoa/thường và pending registration. Giữ quy tắc pending còn hạn do email khác giữ tên; cùng email hoặc pending hết hạn được phép tiếp tục. Không tra cứu email đã có tài khoản, không trả thông tin email/account.
- Không tạo/sửa user, pending registration, token hoặc gửi email trong bước kiểm tra. `register` cũ vẫn kiểm tra lại để xử lý tên bị người khác lấy sau bước đọc. Không thay contract đăng ký/OTP cũ.
- Email không hợp lệ gửi null ở bước kiểm tra tên; email vẫn phải vượt validation trước khi gọi đăng ký. Khi sửa email, kết quả tên cũ bị bỏ vì quyền dùng lại pending handle phụ thuộc email.
- Không migration, không đọc secrets, không chạy ứng dụng/migration/database thật.

## Files

Sửa: AuthController.java, AuthService.java, static/js/auth-register.js, templates/auth/register.html (asset version), AuthControllerWebTest.java, AuthServiceRegisterTest.java; các fixtures register-validation.cjs và registration-business.cjs được bổ sung route kiểm tra mới. Fixture business hiện kiểm tra lỗi inline và sửa lại giá trị trước retry theo hành vi mới.

Mở rộng register-username-taken.cjs từ lượt trước: một lần bấm phát hiện đồng thời tên trùng/email sai/mật khẩu sai; khung lặp không hiện; không gọi register; sửa tên, rate limit, OTP và tên bị chiếm giữa hai bước. Không sửa assertion để che lỗi.

## Kiểm chứng thực tế

- `mvn --batch-mode '-Dtest=AuthServiceRegisterTest,AuthControllerWebTest,RegistrationBusinessValidationTest,AuthTemplateCspTest' test`: BUILD SUCCESS; 43 tests đạt, không failures/errors/skips. Có cases normalization, read-only effects, pending ownership/expiry, reserved name, input validation, rate limit và response không trả tokens/email.
- register-username-taken.cjs: matrix 8 tổ hợp desktop/mobile, sáng/tối, reduced motion; 8 tổ hợp đạt, không page errors. Dùng template và assets thật, API fixtures; kiểm tra 3 lỗi chung lượt, registration race và preflight lỗi không tạo tài khoản.
- register-validation.cjs: 8 tổ hợp đạt, gồm OTP resend, retry, bàn phím/focus, không overflow, CSRF và request payload.
- registration-business.cjs: 17 bộ dữ liệu validation đạt; giữ readonly lúc gửi, timeout recovery, resend, restored form và OTP redirect; 4 đăng ký fixture, không viết tài khoản thật.
- Đã xem ảnh mobile dark của trường hợp ba lỗi; không còn khung tên trùng riêng. JS syntax và git diff --check đạt. Không tuyên bố toàn bộ backend suite hoặc live DB/STOMP/FCM đã kiểm chứng.

## Áp dụng và rollback

Khởi động lại backend và Ctrl+F5 trang đăng ký. Deploy backend có endpoint mới cùng frontend. Rollback nhóm AuthController/AuthService và auth-register.js/template cùng nhau; không thay schema/history. Các sửa social và khởi động từ lượt trước được giữ nguyên.

Trạng thái: Implemented / Partially Tested; chưa kiểm thử đăng ký trên database và dịch vụ email thật.
