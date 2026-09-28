# Các nghiệp vụ bổ sung

## Giao diện

- `/settings`: yêu cầu theo dõi, danh sách chặn, thiết lập/tắt 2FA, vô hiệu hóa và xóa tài khoản.
- `/profile/edit`: bật tài khoản riêng tư. Người theo dõi mới phải được duyệt; các quan hệ theo dõi có sẵn được giữ lại.
- `/compose`: chọn Công khai / Bạn bè / Chỉ mình tôi, thêm khảo sát 2–6 lựa chọn với thời hạn 1–168 giờ.
- Trên bài viết: lưu, báo cáo, khảo sát; tác giả được sửa và xem lịch sử.
- Trên hồ sơ người khác: chặn và báo cáo tài khoản. Bình luận có nút báo cáo.
- `/saved`: các bài đã lưu mà người dùng vẫn có quyền xem.
- `/insights`: tổng số bài, reaction, bình luận, đăng lại và quan hệ theo dõi của tài khoản.
- `/stories`: đăng và xem tin văn bản tồn tại 24 giờ; tác giả có thể xóa sớm.
- `/admin/moderation`: báo cáo đang mở và nội dung FLAGGED; xem nội dung, chấp thuận, ẩn hoặc bác báo cáo.
- `#hashtag` liên kết đến tìm kiếm; `@username` liên kết đến hồ sơ và gửi thông báo nếu người được nhắc có quyền xem bài.

Bạn bè được định nghĩa là hai tài khoản theo dõi lẫn nhau. Block áp dụng hai chiều với hồ sơ, bài viết, follow, bình luận, tin nhắn trực tiếp và cuộc gọi. Quan hệ follow và bạn thân bị gỡ khi chặn, không tự khôi phục khi bỏ chặn. Thành viên vẫn có thể cùng tồn tại trong nhóm chat chung.

## Cấu hình

Tài khoản quản trị dùng role SQL `ROLE_ADMIN`, độc lập với vai trò trong cộng đồng. Đặt `APP_ADMIN_USERNAME` bằng username của một tài khoản đã xác thực rồi khởi động ứng dụng để cấp quyền quản trị cho tài khoản đó. Không có mật khẩu admin mặc định được thêm vào.

PostgreSQL cần các cột mới trên `users`: `self_deactivated` (boolean, nullable), `tokens_revoked_at` (timestamp with time zone, nullable). Cấu hình phát triển hiện tại dùng `ddl-auto=update`. Nếu triển khai với `validate`, chạy [migration SQL](deploy/migrations/20260925_social_features.sql) trước khi khởi động.

MongoDB bổ sung collection cho block, yêu cầu follow, báo cáo, bài đã lưu, vote, story và bảo mật tài khoản. Các index được tạo khi ứng dụng khởi động. Story được lọc theo hạn ngay khi đọc; TTL dọn dữ liệu vật lý sau đó. Bài cũ không có visibility được coi là công khai. Lịch sử sửa được ghi nguyên tử cùng bài viết, chỉ bắt đầu từ các lần sửa sau khi triển khai.

Cache khám phá dùng Caffeine trong môi trường local. Đặt `REDIS_CACHE_ENABLED=true` và `REDIS_URL=redis://host:6379` để dùng Redis khi triển khai; `APP_CACHE_TTL` điều chỉnh TTL, mặc định hai phút. Quyền block, visibility và dữ liệu chat không được cache.

Ảnh và video được tải lên vùng tạm trên Cloudinary rồi gửi URL tới `POST /moderate-media` của moderation service với payload `url` và `mediaType`. Media bị `FLAGGED` hoặc `HIDDEN` sẽ bị xóa khỏi Cloudinary và request bị từ chối. Khi moderation service không phản hồi, kết quả là `unknown` để giữ hành vi fail-open hiện tại.

2FA dùng TOTP 6 số, chu kỳ 30 giây, tương thích Authenticator. Có tám mã khôi phục dùng một lần, chỉ hiển thị lúc bật. Khóa TOTP được mã hóa AES-GCM; cấu hình `APP_SECURITY_ENCRYPTION_KEY` làm khóa ổn định, mặc định dẫn xuất từ `JWT_SECRET`. Không thay khóa mã hóa tùy tiện vì sẽ không đọc được các thiết lập 2FA đã lưu. Mã TOTP đã dùng bị từ chối sử dụng lại. Cơ chế TOTP và dữ liệu kiểm thử tham chiếu [RFC 6238](https://www.rfc-editor.org/rfc/rfc6238).

Vô hiệu hóa ẩn tài khoản và thu hồi phiên hiện tại; đăng nhập đúng mật khẩu và 2FA sẽ kích hoạt lại. Xóa tài khoản là ẩn danh hồ sơ và xóa nội dung bài/bình luận/lịch sử/story; không xóa vật lý hàng `users` để giữ tham chiếu dữ liệu. Lịch sử tin nhắn của người nhận, nhật ký kiểm duyệt, bản sao lưu và file đã tải lên dịch vụ media không nằm trong thao tác xóa này.

## Kiểm tra

```powershell
mvn test
node --check src/main/resources/static/js/app.js
node --check src/main/resources/static/js/chat.js
node --check src/main/resources/static/js/features.js
```

Các unit test không kết nối database. Test `AlouteApplicationTests` chỉ chạy khi có `DB_URL`. Workflow `.github/workflows/ci.yml` cung cấp PostgreSQL, MongoDB và Redis để chạy build tích hợp trên push và pull request, đồng thời lưu báo cáo JaCoCo HTML thành artifact `jacoco-report`. Kiểm thử trình duyệt với hai tài khoản thường và một tài khoản admin vẫn cần thiết cho upload, email và WebSocket trong môi trường triển khai.

## Phản hồi và truy vấn

Quyền đọc danh sách được nạp theo lô, dùng lại dữ liệu tác giả khi dựng response. Feed trang đầu bắt đầu từ 40 ứng viên thay vì 120, chỉ mở rộng khi không đủ bài được phép xem, vẫn giữ giới hạn ứng viên 200. Các ứng viên được xếp hạng bằng độ mới suy giảm theo thời gian, like, bình luận, đăng lại, quan hệ follow và tín hiệu bạn bè đăng lại. Đây là ranking heuristic có thể giải thích, chưa phải mô hình ML học từ hành vi. Khả năng phân trang sâu quá tập ứng viên 200 chưa được thay đổi. Không cache quyền truy cập giữa các request.

Test `FeedQueryBudgetTest` xác nhận feed 20 bài công khai, không có follow/repost, dùng 6 lời gọi repository/Mongo; test `ContentAccessBatchTest` xác nhận kiểm tra 120 bài công khai dùng 2 truy vấn theo lô. Đây là kiểm đếm bằng mock, không phải benchmark độ trễ database thực tế. Các thành phần sidebar và xác thực vẫn có truy vấn riêng.

Reaction kiểm tra quyền trên document, không dựng cả response bài viết. Danh sách chat, hồ sơ, thông báo, bình luận, bài lưu, story và trending cũng dùng kiểm tra theo lô. Hover menu không còn tải trang ngầm. Badge tái sử dụng số đếm khi chuyển trang mềm, giới hạn tải lại mỗi 30 giây; các thao tác cập nhật trực tiếp vẫn thay đổi badge trên giao diện.

Template cache mặc định bật. Khi chỉnh HTML và cần thấy thay đổi ngay, đặt `THYMELEAF_CACHE=false`; khi đo hiệu năng dùng `true`. Khởi động lại ứng dụng để áp dụng bản backend và cấu hình mới, tải lại trình duyệt để nhận JavaScript mới.
