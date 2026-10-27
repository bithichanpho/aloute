# Khôi phục Redis local — 25/10/2026

## Kiểm tra lại 26/10/2026

Backend local PID 20136 chạy cổng 8081, còn Redis local đã dừng, không có listener 6379. Bật lại bằng helper hiện có, giữ dữ liệu AOF. Lần PING đầu trả LOADING; sau nạp xong status trả PONG, `loading:0`, `aof_enabled:1`, `aof_last_write_status:ok`. Xác nhận backend PID 20136 có kết nối TCP Established tới 127.0.0.1:6379. Không cần restart backend để Lettuce kết nối lại.

Helper PowerShell đã bổ sung chờ PONG tối đa 20 giây khi start, không coi exit code 0 kèm LOADING là sẵn sàng. Kiểm tra start/status trên instance đang chạy và parse cú pháp PowerShell; không thử dừng Redis hoặc tạo outage khi backend đang dùng. Sau Windows restart/WSL shutdown vẫn cần chạy helper start; chưa cấu hình tự khởi động cùng Windows. Không flush/read user keys, sửa private/DB/config ứng dụng hoặc tạo startup task.

## Nguyên nhân đã kiểm tra

- Backend Java/STS PID 30232 vẫn lắng nghe tại cổng 8081.
- Trước xử lý, Windows và WSL không có listener tại localhost:6379; thử kết nối Windows không thành công.
- Docker Desktop daemon không chạy. Compose hiện tại cũng không public cổng Redis ra Windows, nên chỉ bật Compose Redis chưa đủ cho backend chạy trực tiếp trong STS.
- `PresenceService` có cơ chế dự phòng local sessions và ghi WARN khi Redis không kết nối được. Các chỉnh sửa follow-request/private-profile trước đó không thay file này hoặc cấu hình Redis.
- Không đọc `.env`, credentials hoặc nội dung keys Redis của người dùng.

## Đã thực hiện

Khởi động Redis 8.0.5 đã có sẵn trong Ubuntu/WSL. Sao chép redis-server, redis-cli và hai thư viện cần thiết sang runtime riêng để không phụ thuộc thư mục runtime kiểm thử trước đó. Không cài system package, không chạy Docker, không đổi production/external infrastructure hoặc backend đang chạy.

Runtime, config và dữ liệu local:

```text
/home/buiph/.local/share/aloute/redis/
  runtime/bin/redis-server
  runtime/bin/redis-cli
  runtime/lib/
  data/
  logs/redis.log
  redis.conf
  redis.pid
```

Launcher: `/home/buiph/.local/bin/aloute-redis`. Thư mục runtime trước đó không tồn tại; không ghi đè runtime hay dữ liệu có sẵn. Cấu hình bind **127.0.0.1 và ::1**, protected mode, AOF + appendfsync everysec, giới hạn RAM 256 MB và noeviction để không tự đẩy bỏ state/rate-limit keys khi thiếu RAM. Redis này chỉ phục vụ phát triển local, không có password theo mẫu localhost của dự án; không mở cổng cho LAN/Internet. Không đổi URL hoặc credentials ứng dụng.

Thêm `scripts/local-redis.ps1` để gọi launcher có sẵn. Launcher start idempotent; từ chối port đang bị process khác chiếm. Stop chỉ thực hiện khi PID/executable đúng runtime do tác vụ tạo; shutdown giữ dữ liệu. Không flush/reset/delete dữ liệu, không chạm PostgreSQL hoặc đổi quyền riêng tư.

## Kết quả thật

- WSL `aloute-redis status`: **PONG**.
- Helper PowerShell `-Action status` và `-Action start` trên process đang chạy đều **PONG**, không tạo thêm Redis. Socket listener chỉ có 127.0.0.1 và ::1.
- Raw RESP PING từ Windows qua **127.0.0.1:6379** và **[::1]:6379**: **+PONG**.
- Backend PID 30232 tự nối lại: **ESTABLISHED**, remote 127.0.0.1:6379, không restart backend.
- Một GET public read-only tới `/api/posts/user/1/cursor?size=1`: **HTTP 200**, success=true. Không in nội dung bài/tài khoản.
- Redis commandstats ghi nhận backend HELLO, EVAL, sorted-set presence operations và rate-limit operations. EVALSHA ban đầu có NOSCRIPT do Redis mới chưa có script cache; client fallback EVAL chạy thành công. Không gọi MONITOR/KEYS/GET để xem dữ liệu của người dùng.

Đây là khôi phục dependency tại runtime, không phải thay đổi Java, REST/WS contract hay database. Không load test, không restart để thử persistence khi backend đang dùng. Không khẳng định quan sát được toàn bộ console WARN của STS; bằng chứng trực tiếp là kết nối live và commands Redis đã chạy.

## Bật, kiểm tra, dừng

Từ thư mục repository trong PowerShell:

```powershell
.\scripts\local-redis.ps1 -Action start
.\scripts\local-redis.ps1 -Action status
.\scripts\local-redis.ps1 -Action stop
```

Cách gọi trực tiếp nếu PowerShell chặn script:

```powershell
wsl -d Ubuntu -- /home/buiph/.local/bin/aloute-redis start
wsl -d Ubuntu -- /home/buiph/.local/bin/aloute-redis status
wsl -d Ubuntu -- /home/buiph/.local/bin/aloute-redis stop
```

Sau restart Windows hoặc `wsl --shutdown`, cần chạy lại start. Không tạo startup task hoặc thay systemd/global configuration. Nếu muốn rollback, dùng stop; dữ liệu vẫn được giữ. Dừng Redis sẽ làm các chức năng phụ thuộc Redis trở lại cơ chế dự phòng hiện có.
