# Đối chiếu hai thư mục moderation — 28/10/2026

Thư mục tham chiếu được người dùng chỉ định: `D:\LTWEB\src\moderation`.
Thư mục dự án: `D:\aloute-develop123\aloute-develop123\moderation`.
Không sửa hoặc thay thế mã nguồn của hai thư mục trong lượt kiểm tra này.

## Kết quả đối chiếu

Bỏ qua cache, bytecode, môi trường Python và các file cấu hình bí mật. Có 10 đường dẫn sản phẩm/test trong hợp của hai thư mục:

- 6 file giống hoàn toàn: `.dockerignore`, `Dockerfile`, `docker-compose.standalone.yml`, `requirements.txt`, `requirements-dev.txt`, `tests/test_main.py`.
- 1 file khác: `main.py`.
- 3 file chỉ có ở dự án: `README_LOCAL_VI.md`, `tests/test_media_reliability.py`, `tests/test_ffmpeg_local.py`.
- Không có file sản phẩm chỉ ở thư mục tham chiếu mà bị thiếu trong dự án.

## Khác biệt có ảnh hưởng

| Nội dung | Tham chiếu D:\LTWEB | Dự án hiện tại |
|---|---|---|
| Video có audio nhưng không giải mã được | Bỏ audio, tiếp tục xét hình | Trả lỗi 503, chưa đưa ra quyết định an toàn |
| Dịch vụ chép lời từ chối audio với 400/413/422 | Bỏ audio, tiếp tục xét hình | Trả 503 để có thể thử lại |
| Không xác minh được thời lượng video | Lấy hình ở các thời điểm cố định | Trả 503; không tự kết luận đã kiểm tra đủ |
| Thiếu khung hình dự kiến | Có thể tiếp tục với số hình còn lại | Yêu cầu đủ các hình đã lên kế hoạch |
| Ảnh không hoàn chỉnh | Cho phép giải mã ảnh truncated | Từ chối ảnh chưa giải mã đầy đủ |
| Giới hạn pixel mặc định | 100 triệu pixel | 30 triệu pixel; có thể cấu hình tối đa 100 triệu |
| Phản hồi upstream | Chấp nhận mã dưới 400 | Yêu cầu 2xx và còn trong thời hạn kiểm tra |
| Công việc xử lý video song song | Một số bước chờ Future không có timeout riêng | Chờ có timeout; dọn tài nguyên sau khi công việc đã chạy kết thúc |

Thuật toán chấm điểm, luật lọc chữ và các endpoint không thay đổi giữa hai bản. Bản dự án bổ sung kiểm tra độ đầy đủ; điều này cũng có thể khiến ảnh độ phân giải lớn, ảnh hỏng hoặc video thiếu metadata bị từ chối/thử lại nhiều hơn. Không nên chép đè bản D:\LTWEB lên dự án nếu muốn giữ các kiểm tra này.

## Kiểm chứng thực tế

- Bộ test có sẵn tại D:\LTWEB: **8/8 đạt**.
- Bộ Python của dự án: **22/22 đạt**, gồm chạy FFmpeg/ffprobe thật với clip tổng hợp, còn provider được giả lập.
- Dùng cùng 13 ca kiểm tra media reliability của dự án để kiểm tra `main.py` ở D:\LTWEB: **7 đạt, 6 không đạt**. Các ca không đạt: audio không trích được, ba trạng thái từ chối chép lời 400/413/422, không xác minh được thời lượng, thiếu khung hình về cuối video. Module được import và xác nhận đúng là `/mnt/d/LTWEB/src/moderation/main.py`.
- Kiểm tra thêm 58 ca Java liên quan moderation/upload: **55 đạt, 3 không đạt** trong `PostModerationWorkerTest`. Cả ba đã được ghi nhận trong audit ghép ZIP trước đó: test yêu cầu không gọi notification, nhưng worker gửi thông báo kết quả kiểm duyệt cho tác giả. Trạng thái FLAGGED/HIDDEN vẫn đạt assertion trước bước kiểm tra notification; chưa sửa test hay thay đổi nghiệp vụ trong lượt này.
- Lúc kiểm tra, dịch vụ local cổng 8000 chưa phản hồi. Đã chạy lại helper có sẵn `deploy/start-local-moderation.ps1`; `/healthz` sau đó trả `status=ok`, `provider=moderation-api`, `pendingMediaJobs=0`.
- Không gọi OpenAI/Cloudinary bằng nội dung thật trong lượt này. Health local không xác nhận khóa, quota hoặc chất lượng quyết định của provider thật.

Evidence cục bộ được Git bỏ qua: `target/moderation-folder-comparison-20261028.json`, `target/moderation-reference-main.diff`, `target/moderation-reference-regression.log`, `target/moderation-zip-check-java.log` và các báo cáo Surefire liên quan.
