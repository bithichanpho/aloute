# Gọi thoại/video và lịch sử cuộc gọi

## Một backend dùng chung trong LAN, không cần Docker

Hai máy mở **cùng một địa chỉ HTTPS của backend**. Không mở `localhost` trên máy bạn bè để truy cập backend của máy chủ. Máy chủ vẫn cần PostgreSQL và các dịch vụ mà cấu hình hiện tại bật; chế độ LAN không tự cài hoặc thay chúng.

Windows PowerShell:

```powershell
.\setup-lan-call.ps1 -LanIp 192.168.1.25 -Port 8080 -TrustCertificate
```

Thay IP bằng IPv4 của máy chạy backend; bỏ `-LanIp` để dò mạng đang có gateway. `-PrepareOnly` chỉ tạo chứng chỉ và in địa chỉ, chưa khởi động ứng dụng. Chỉ dùng helper trên máy phát triển. Helper tạo CA/chứng chỉ trong kho cá nhân; chỉ thêm CA vào kho tin cậy khi có `-TrustCertificate`. Không chạy helper tự động trong CI/production.

Trên máy còn lại, nhận **chỉ** `dev-certs/aloute-lan-ca.cer`, kiểm tra nguồn gửi rồi tin cậy CA đó trong tài khoản Windows của mình. Không chia sẻ P12, mật khẩu, `.env` hay khóa Firebase. Thư mục `dev-certs/` được Git bỏ qua. Nếu IP thay đổi, dùng `-ForceNewCertificate` có chủ đích và tin cậy lại CA mới. Helper không tự xóa chứng chỉ cũ hoặc mở firewall; có thể gỡ CA phát triển khỏi kho tin cậy khi không sử dụng nữa.

Cho phép TCP cổng đã chọn trên mạng Private nếu firewall chặn. Cả hai trình duyệt cần cấp quyền camera/micro. Wi-Fi có client isolation hoặc NAT/firewall hạn chế có thể cần TURN truy cập được từ cả hai thiết bị. Profile `lan-call` tắt Docker Compose và STOMP relay, dùng một backend; không thay hoặc xóa cấu hình broker/TURN hiện có. TURN có thể cấu hình thêm bằng biến môi trường hiện hành.

## Nhiều backend / mạng khác nhau

Profile tùy chọn `call-distributed` yêu cầu PostgreSQL, Redis và STOMP broker dùng chung, cùng TURN mà các trình duyệt truy cập được. Cấu hình `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_URL`, `APP_WEBSOCKET_BROKER_RELAY_HOST`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `APP_WEBRTC_TURN_URLS`, `TURN_SHARED_SECRET`, `APP_CORS_ALLOWED_ORIGINS` ngoài Git. Giá trị phải thống nhất giữa các node và phù hợp với HTTPS của frontend.

Kiểm tra khởi động xác nhận cấu hình, kết nối DB/Redis và TCP tới broker; TCP thành công **không chứng minh** STOMP login hay TURN relay hoạt động. DNS nội bộ của dịch vụ dùng chung vẫn hợp lệ. Không dùng `localhost` của từng laptop để mô tả dịch vụ dùng chung. Profile không tự tạo hay chỉnh hạ tầng; Docker Compose hiện tại được giữ nguyên.

## Lịch sử và thời lượng

Backend quyết định trạng thái `MISSED`, `CANCELLED`, `DECLINED`, `ENDED`. Thời lượng tính từ `answered_at` đến `ended_at`, làm tròn xuống giây, tối thiểu 0; không cộng thời gian đổ chuông. Trường JSON bổ sung `callDurationSeconds` có thể null ở bản ghi cũ.

Tất cả cuộc gọi mới kết thúc được đưa vào journal phục hồi hiện có. Worker ghi CALL message và đánh dấu hoàn tất trong giao dịch PostgreSQL; riêng MISSED còn ghi notification outbox. `callId` duy nhất chặn trùng khi retry. Worker lỗi sẽ thử lại có backoff, tối đa 20 lần, sau đó giữ trạng thái FAILED để vận hành kiểm tra. WebSocket gửi sau commit; lịch sử vẫn đọc được từ DB nếu gửi realtime thất bại. Không cam kết giao nhận realtime exactly-once.

Migration `V20261112` chỉ thêm index cho journal mở rộng, không sửa/xóa hoặc phát lại lịch sử cũ. Khi quay lại bản ứng dụng cũ, giữ DB và các CALL message; các journal terminal ngoài MISSED đang chờ cần worker bản mới để xử lý. Không chạy Flyway clean, sửa checksum hoặc xóa dữ liệu để rollback.

Trước triển khai thực tế cần kiểm tra hai thiết bị, HTTPS trust, quyền thiết bị, STOMP reconnect và TURN qua mạng đích. Kiểm thử giả lập không thay thế được bước này.
