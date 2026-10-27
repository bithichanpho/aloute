# Chạy kiểm duyệt cục bộ không dùng Docker

Backend gửi ảnh/video chat tới FastAPI ở `http://localhost:8000`. Nếu dịch vụ không chạy hoặc phản hồi quá hạn, backend chặn gửi media; tắt kiểm duyệt không phải cách khôi phục upload.

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

Ảnh/video chat phải được duyệt trước khi trả URL gửi tin nhắn. Bài đăng giữ luồng PENDING/worker riêng. PDF/ZIP/DOC và raw files hiện bị `UploadPurpose` từ chối vì chưa có bộ quét tệp chuyên dụng; helper không mở rộng danh sách cho phép hoặc bỏ kiểm tra sở hữu media.
