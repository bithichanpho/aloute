# Khôi phục moderation cục bộ không Docker — 28/10/2026

## Chẩn đoán

Người dùng xác nhận lỗi chỉ khi gửi media trong chat, không phải tin nhắn chữ. Khi kiểm tra, backend cổng 8081 có listener nhưng TCP/healthz cổng 8000 không phản hồi. `ModerationService.checkMediaForUpload` báo `MEDIA_SERVICE_UNAVAILABLE` khi I/O lỗi/timeout. Chat upload chỉ trả URL sau kiểm duyệt; vì vậy không có message media được gửi khi bước này thất bại.

WSL Ubuntu đã có Python 3.14.4 nhưng chưa có ffmpeg/ffprobe. Không đọc/in giá trị secrets, không gọi provider bằng media người dùng để chẩn đoán.

## Đã làm

- Cài `python3-venv`, FFmpeg 8.0.1 và dependency từ requirements hiện có trong Ubuntu WSL; venv riêng tại `~/.local/share/aloute/moderation/venv`.
- Thêm `deploy/start-local-moderation.ps1` và `.sh`; script chuẩn bị venv/requirements, khởi động Uvicorn bằng tiến trình WSL ẩn và giữ service sống sau khi helper kết thúc. Không dùng Docker, không tự dừng process đang chiếm cổng.
- Uvicorn tự đọc `.env` bằng dotenv trong tiến trình dịch vụ; không source file bằng shell, không đưa credential vào command line/code/báo cáo. `.env` không thay đổi.
- Service bind `127.0.0.1:8000`; đã khởi động thật và giữ chạy để backend sử dụng. Gọi helper lần hai thấy sẵn sàng và không tạo thêm service.
- Thêm hướng dẫn `moderation/README_LOCAL_VI.md`, bảo đảm helper Bash checkout LF bằng `.gitattributes`.
- Không tắt kiểm duyệt, không đổi whitelist file/authorization, không sửa cuộc gọi, không thêm/chạy migration hoặc restart backend.

## Kiểm chứng

- Windows GET `http://127.0.0.1:8000/healthz` và `http://localhost:8000/healthz`: HTTP 200, status `ok`, provider `moderation-api`.
- **32 Java test đạt**: UploadControllerTest (5), MediaUploadModerationServiceTest (5), ModerationServiceTest (9), ModerationRetryPolicyTest (2), UploadPurposeTest (11). Log/result trong `target/local-moderation-regression.log`, `local-moderation-java-results.json`.
- **22 Python test đạt** trên venv WSL; gồm integration mới `test_ffmpeg_local.py` tạo clip màu/tone 2 giây, chạy ffprobe/ffmpeg thật để lấy đủ frame và audio; provider dùng test double, không gửi request thật tới OpenAI/CDN.
- PowerShell AST và `bash -n` đạt. Health tiếp tục đạt sau khi lệnh launcher đã trả về.
- Chưa xác minh ảnh/video thực tế của người dùng qua Cloudinary/OpenAI; health không chứng minh quota/credentials upstream. Tài liệu/raw PDF/ZIP/DOC vẫn không hỗ trợ theo UploadPurpose hiện hành.

## Dùng lại

Chạy `.\deploy\start-local-moderation.ps1` sau khi khởi động lại máy/WSL. Flag `-InstallDependencies` dành cho máy chưa có prerequisites. Log runtime trong `target/local-moderation.log` và `.stdout.log` (Git bỏ qua); kiểm tra tại máy mình, không chia sẻ giá trị bí mật.
