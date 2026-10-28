# Tự khởi động Redis và moderation cùng ứng dụng

Backend gửi ảnh/video chat tới FastAPI ở `http://localhost:8000`. Nếu dịch vụ không chạy hoặc phản hồi quá hạn, backend chặn gửi media; tắt kiểm duyệt không phải cách khôi phục upload.

Khi bấm Run `AlouteApplication` hoặc chạy JAR, backend tự kiểm tra Redis và moderation trước khi khởi động các worker. Dịch vụ đã sẵn sàng sẽ được dùng lại; dịch vụ chưa chạy được bật bằng Docker Compose. Cách này dùng được trên Windows, macOS và Linux. Không cần cài Redis, Python hoặc FFmpeg riêng trên máy mới.

Thiết lập một lần trên máy mới:

1. Cài Java 21 trở lên nếu chạy JAR; cài và mở Docker có Compose, dùng Linux containers.
2. Đặt JAR và `.env` vào cùng một thư mục, cấu hình database và các dịch vụ ngoài như trước. Moderation cần `OPENAI_API_KEY` và `MODERATION_API_TOKEN` riêng tối thiểu 32 byte. Không đưa file chứa khóa vào Git.
3. Chạy `java -jar aloute-0.0.1-SNAPSHOT.jar` từ thư mục đó, hoặc bấm Run trong IDE khi dùng mã nguồn. App tự dựng/chạy những dịch vụ còn thiếu và chờ healthcheck đạt.

Mã Python, requirements, Dockerfile và Compose của các dịch vụ được đóng gói ngay trong JAR. App lấy chúng từ JAR vào thư mục `.aloute/local-services` trong thư mục người dùng; không dùng đường dẫn `D:\...` của máy lập trình viên. Khóa được truyền qua môi trường tiến trình, không ghi vào các file được trích ra. Lần dựng đầu tiên cần Internet để tải image/package và có thể mất vài phút; thời gian chờ mặc định mỗi dịch vụ là 5 phút, đổi bằng `LOCAL_SERVICES_STARTUP_TIMEOUT`.

Tính năng áp dụng cho Redis local `localhost:6379` không có xác thực và moderation `http://localhost:8000` (hoặc địa chỉ loopback tương đương). Dịch vụ ở máy khác, cổng tùy chỉnh, Compose do Spring quản lý và các profile `prod`, `production`, `call-distributed`, `test` được giữ nguyên cách quản lý riêng. Có thể tắt bằng `LOCAL_SERVICES_AUTO_START=false`. Khi dừng backend, Redis/moderation vẫn chạy để lần mở sau dùng lại.

Nếu không khởi động được, backend báo rõ dịch vụ và dừng khởi động. Log Docker nằm ở `.aloute/local-services/startup-redis.log` hoặc `startup-moderation.log` trong thư mục người dùng. Việc bật service không tự sửa khóa API/quota, kết nối Internet hoặc lỗi kiểm duyệt upstream.

## Dùng lại WSL đã được cài trên Windows

Nếu Docker chưa sẵn sàng nhưng đang chạy mã nguồn trên Windows, ứng dụng vẫn có thể dùng các helper WSL sẵn có. Log nhánh này là `target/local-services-redis.log` hoặc `target/local-services-moderation.log`. JAR mang sang máy mới không phụ thuộc vào nhánh WSL này.

Trên Windows có Ubuntu WSL, chạy từ thư mục repository:

```powershell
.\deploy\start-local-moderation.ps1 -InstallDependencies
```

Lần đầu helper cài `ffmpeg`, `python3-venv` từ Ubuntu và các package của `moderation/requirements.txt` vào venv riêng trong WSL. Những lần sau chỉ cần:

```powershell
.\deploy\start-local-moderation.ps1
```

Ứng dụng FastAPI tự nạp `.env` qua dotenv của Uvicorn; helper không source `.env` bằng shell, không hiển thị/copy giá trị khóa. Cần cấu hình riêng `OPENAI_API_KEY`, `MODERATION_API_TOKEN` (tối thiểu 32 byte), và token phải khớp backend. Không đưa các giá trị đó vào Git hoặc gửi vào chat.

Service chỉ bind loopback `127.0.0.1:8000`. Helper kiểm tra health trước/sau khởi động và không tự dừng tiến trình khác đang chiếm cổng. Windows giữ một tiến trình WSL ẩn để tránh WSL idle shutdown. Không tự khởi động lại backend hoặc sửa database/migration.

```powershell
Invoke-RestMethod http://127.0.0.1:8000/healthz
```

`status=ok` xác nhận FastAPI/FFmpeg đang sẵn sàng; không chứng minh OpenAI credentials/quota hoặc CDN đang hoạt động. Trường `upstreamConsecutiveFailures`/`upstreamLastStatus` hỗ trợ chẩn đoán sau những request thực tế. Kiểm tra log `target/local-moderation.log` ở máy mình nếu lỗi; không chia sẻ thông tin bí mật.

Ảnh/video chat phải được duyệt trước khi trả URL gửi tin nhắn. Bài đăng giữ luồng PENDING/worker riêng. Tệp đính kèm chat (PDF/ZIP/DOC và các định dạng khác) được gửi qua `/api/upload/file`, tối đa 1 MB mỗi tệp, không quét nội dung. Backend vẫn kiểm tra đăng nhập và quyền sở hữu tệp khi gửi tin nhắn.
