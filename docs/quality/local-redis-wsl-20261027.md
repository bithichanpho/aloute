# Redis local không Docker — 27/10/2026

## Chẩn đoán

Log người dùng cung cấp cho thấy backend đã khởi động ở cổng 8081; lỗi là RedisConnectionFailureException trong cache/presence, không phải migration thất bại. Kiểm tra ban đầu không có listener Redis ở 6379. Spring đang dùng default profile, cache Redis bật theo cấu hình hiện có.

## Đã thực hiện theo lựa chọn của người dùng

- Cài redis-server và redis-tools từ kho Ubuntu trong distro WSL Ubuntu 26.04 LTS có sẵn; phiên bản thực cài Redis 8.0.5.
- Bật redis-server qua systemd; trạng thái active/enabled. Service tự khởi động khi Ubuntu WSL khởi động, không tạo Windows startup task.
- Phát hiện WSL tự tắt sau khi các lệnh kết thúc dù systemd service còn chạy. Thêm deploy/start-local-redis.ps1 để giữ một phiên Ubuntu chạy nền ẩn và khởi động Redis; gọi lại không sinh thêm phiên giữ nền. Đây là helper cục bộ, không đổi startup của Windows.
- Giữ bind mặc định chỉ 127.0.0.1 và ::1, port 6379. Không mở cổng LAN, không dùng Docker.
- Không sửa .env, không đọc secrets, không đổi database/migration hay mặc định Redis của backend. Không khởi động lại backend hoặc chạy migration.

## Kiểm chứng thực tế

- redis-cli ping bên trong WSL: PONG.
- Redis protocol PING từ Python chạy trên Windows đến 127.0.0.1, localhost và ::1: cả ba trả +PONG.
- SET NX EX 30 / GET từ Windows với key kiểm tra UUID riêng: đạt; chỉ DEL đúng key do kiểm tra tạo ra. Không flush hoặc xóa cache ứng dụng.
- GET http://localhost:8081/actuator/health trên backend đang chạy: HTTP 200, status UP. Endpoint /actuator/health/redis trả 404 nên không có kết quả chi tiết riêng từ endpoint này.
- Gọi lại start-local-redis.ps1 và đối chiếu PID helper trước/sau: giữ nguyên, không sinh thêm helper. Trên máy này wsl.exe có cả launcher và tiến trình con nên không đồng nhất số process Windows với số phiên giữ nền.
- Kiểm tra PING từ Windows ở mốc 0/30/60/90 giây mà không chạy thêm lệnh WSL: cả bốn đạt; health backend kiểm tra lại cuối khoảng chờ: HTTP 200 UP. Script PowerShell parse không lỗi; git diff --check đạt; migration không thay đổi.
- Không chạy load test hoặc toàn bộ test Java vì lần này không sửa mã ứng dụng.

## Dùng những lần sau

Nếu Redis chưa chạy, dùng PowerShell:

```powershell
.\deploy\start-local-redis.ps1
```

Để dừng Redis local khi cần:

```powershell
wsl -d Ubuntu -u root -- systemctl stop redis-server
```

Không thực hiện stop/uninstall trong lượt sửa này. Tắt hoàn toàn WSL cũng dừng Redis; chạy script trên từ thư mục repository sẽ mở lại Ubuntu, giữ phiên nền và bật service. Không có cơ chế tự chạy script khi Windows đăng nhập.
