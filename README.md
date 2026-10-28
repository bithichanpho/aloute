<p align="center">
  <img src="src/main/resources/static/images/aloute-bird-logo.png" alt="Logo ALOUTE" width="120">
</p>

# ALOUTE

Mạng xã hội trên nền web, hỗ trợ chia sẻ bài viết, kết nối bạn bè, tham gia cộng đồng, nhắn tin và gọi thoại/video.

[Cài đặt](#cài-đặt) · [Cấu hình .env](#cấu-hình-env) · [Docker](#chạy-bằng-docker-compose) · [Tài liệu](#tài-liệu)

ALOUTE sử dụng **Spring Boot** cho backend, **Thymeleaf** cho giao diện và **PostgreSQL** để lưu dữ liệu. Ảnh, video và tệp được lưu trên Cloudinary; dịch vụ FastAPI riêng đảm nhiệm kiểm duyệt nội dung bằng AI. Giao diện hỗ trợ nền sáng/tối và màn hình điện thoại.

## Chức năng

| Nhóm chức năng | Nội dung |
| --- | --- |
| Tài khoản và hồ sơ | Đăng ký, xác nhận email bằng OTP, đăng nhập Google, khôi phục/đổi mật khẩu, cập nhật hồ sơ và ảnh đại diện. |
| Bài viết và bảng tin | Đăng chữ, ảnh, video; chọn quyền xem; sửa bài, xem lịch sử, bình luận, thả cảm xúc, đăng lại và lưu bài. |
| Kết nối và tìm kiếm | Theo dõi, bạn thân, chặn người dùng; tìm người và bài viết, tìm theo hashtag. |
| Nhắn tin và cuộc gọi | Chat cá nhân/nhóm, gửi ảnh, video và tệp; trạng thái đã xem, đang nhập; gọi thoại/video. |
| Cộng đồng | Tạo, khám phá và tham gia cộng đồng; quản lý thành viên, yêu cầu tham gia và duyệt bài. |
| Thông báo | Thông báo trong ứng dụng, số chưa đọc và thông báo đẩy qua Firebase khi được cấu hình. |
| Kiểm duyệt và quản trị | Kiểm duyệt nội dung, tiếp nhận báo cáo, quản lý tài khoản và dashboard dành cho HOST. |

## Công nghệ

- **Ứng dụng:** Java 21, Spring Boot, Spring Security, Thymeleaf, HTML/CSS và JavaScript.
- **Dữ liệu:** PostgreSQL, Spring Data JPA, JSONB, Flyway, Redis và Caffeine.
- **Thời gian thực:** WebSocket/STOMP, WebRTC; RabbitMQ và Coturn khi triển khai nhiều backend.
- **Tích hợp:** Cloudinary, SMTP, Firebase Authentication và FCM.
- **Kiểm duyệt:** Python, FastAPI, FFmpeg và OpenAI API.

## Cài đặt

Cần **JDK 21**, **PostgreSQL** và **Docker có Compose** đang chạy để tự khởi động Redis/moderation local. Maven Wrapper có sẵn; không cần chạy frontend riêng. Có thể dùng dịch vụ đã tự cài hoặc helper Windows/WSL theo [hướng dẫn local](moderation/README_LOCAL_VI.md).

### 1. Lấy mã nguồn và tạo file cấu hình

Ví dụ dưới đây dùng PowerShell. Sau khi lấy mã nguồn, thực hiện các bước tiếp theo từ thư mục gốc dự án:

```powershell
git clone https://github.com/bithichanpho/aloute.git
cd aloute
if (-not (Test-Path -LiteralPath .env)) {
    Copy-Item -LiteralPath .env.example -Destination .env
}
```

Điền `.env` theo [phần cấu hình](#cấu-hình-env) trước khi chạy. Mẫu mặc định dành cho **một backend chạy trực tiếp trên máy**, dùng cổng **8085**, chưa bật RabbitMQ hoặc TURN.

### 2. Chuẩn bị PostgreSQL

Tạo database nếu chưa có:

```powershell
psql -h localhost -U postgres -d postgres -c "CREATE DATABASE aloute;"
```

Điền đúng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`. Khi khởi động, Flyway áp dụng migration và Hibernate kiểm tra ánh xạ bằng `DB_DDL_AUTO=validate`. Tài khoản database cần đủ quyền chạy migration, bao gồm extension `pg_trgm`. Với database đang có dữ liệu, sao lưu và đối chiếu lịch sử migration trước khi nâng cấp.

### 3. Chạy ứng dụng

```powershell
.\mvnw.cmd spring-boot:run
```

Linux/macOS dùng `./mvnw spring-boot:run`. Trong IDE, chọn JDK 21, đặt thư mục làm việc là gốc dự án và chạy `com.aloute.AlouteApplication`.

Backend kiểm tra Redis/moderation local và tự bật dịch vụ còn thiếu theo cấu hình. Lần dựng đầu có thể mất vài phút. Sau log `Started AlouteApplication`, mở **[http://localhost:8085](http://localhost:8085)**, đăng ký và xác nhận OTP qua email.

## Cấu hình .env

File [.env.example](.env.example) liệt kê các biến mẫu. `.env` chứa giá trị riêng của môi trường và được Git bỏ qua; khi mẫu thay đổi, cập nhật các khóa cần thiết vào file đang dùng, không chép đè toàn bộ.

Java đọc `.env` từ **thư mục làm việc hiện tại** qua Spring Config Import. Biến môi trường của tiến trình và tham số khởi động có thể ghi đè giá trị trong file. Sửa cấu hình xong cần khởi động lại ứng dụng; dịch vụ Docker/WSL đã chạy cũng cần nhận cấu hình mới nếu thay khóa của chúng.

Quy tắc định dạng:

- Mỗi dòng là `KEY=value`; không thêm `export` hoặc dấu nháy bao quanh giá trị vì Java đọc file này theo định dạng `.properties`.
- Lưu UTF-8, viết chú thích trên dòng riêng, tránh khai báo trùng khóa.
- Đường dẫn Windows dùng dạng `C:/Users/ten-ban/.secret/firebase-admin.json` để tránh dấu `\` bị hiểu thành ký tự escape.
- Không đưa `.env`, JSON Firebase Admin, `.p12` hoặc mật khẩu chứng chỉ vào Git. Các giá trị `CHANGE_ME` chỉ là chỗ điền, cần thay trước khi dùng chức năng tương ứng.

### 1. Database và địa chỉ website

```dotenv
DB_URL=jdbc:postgresql://localhost:5432/aloute
DB_USERNAME=postgres
DB_PASSWORD=CHANGE_ME_POSTGRES_PASSWORD
DB_SCHEMA=public
DB_DDL_AUTO=validate
DB_POOL_MAX=15
DB_POOL_MIN=3

SERVER_PORT=8085
FRONTEND_BASE_URL=http://localhost:8085
APP_CORS_ALLOWED_ORIGINS=http://localhost:8085
APP_SECURITY_COOKIE_SECURE=false
```

`DB_URL` dùng tiền tố **`jdbc:postgresql://`**. Nếu database ở máy khác, thay host, cổng và tham số SSL theo môi trường đó. `DB_SCHEMA` phải khớp schema thực tế; không dùng `create-drop` với dữ liệu cần giữ.

`SERVER_PORT` là cổng backend; `FRONTEND_BASE_URL` là địa chỉ người dùng mở; `APP_CORS_ALLOWED_ORIGINS` là các origin được phép, phân cách bằng dấu phẩy, gồm giao thức/host/cổng và không có đường dẫn. Khi đổi cổng hoặc chuyển sang HTTPS, cập nhật cả nhóm này. HTTP local dùng cookie secure `false`; HTTPS dùng `true`.

### 2. Khóa bảo mật và thời hạn phiên

| Biến | Cách điền |
| --- | --- |
| `JWT_SECRET` | Chuỗi ngẫu nhiên riêng, tối thiểu 32 byte UTF-8, dùng ký token. |
| `APP_SECURITY_ENCRYPTION_KEY` | Khóa ngẫu nhiên khác `JWT_SECRET`, tối thiểu 32 byte UTF-8, dùng mã hóa dữ liệu bí mật. |
| `MODERATION_API_TOKEN` | Token nội bộ Java ↔ FastAPI, tối thiểu 32 byte UTF-8; hai dịch vụ phải dùng cùng giá trị. |
| `JWT_EXPIRATION` | Thời hạn access token theo **mili giây**, mặc định `900000` = 15 phút. |
| `APP_REFRESH_TOKEN_DAYS` | Thời hạn refresh token theo **ngày**, mặc định `30`. |

Tạo một chuỗi ngẫu nhiên bằng PowerShell; chạy riêng cho từng khóa rồi tự điền vào `.env`:

```powershell
$secretBytes = New-Object byte[] 32
$randomGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $randomGenerator.GetBytes($secretBytes)
    [Convert]::ToBase64String($secretBytes)
} finally {
    $randomGenerator.Dispose()
}
```

Không dùng chung một chuỗi cho tất cả các khóa. Giữ `APP_SECURITY_LEGACY_ENCRYPTION_KEY` trống nếu không thực hiện chuyển đổi dữ liệu mã hóa cũ; thay khóa mã hóa cần có kế hoạch chuyển đổi, không sinh lại tùy tiện mỗi lần chạy.

### 3. SMTP để gửi OTP và khôi phục mật khẩu

```dotenv
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=CHANGE_ME_SMTP_USERNAME
MAIL_PASSWORD=CHANGE_ME_SMTP_PASSWORD
MAIL_FROM=CHANGE_ME_VERIFIED_SENDER_EMAIL
```

Đây là ví dụ máy chủ SMTP; điền host và thông tin xác thực của dịch vụ nhóm sử dụng. Cấu hình hiện tại bật **SMTP authentication và STARTTLS**, phù hợp với cổng 587. `MAIL_FROM` phải là địa chỉ được phép gửi; dùng mật khẩu ứng dụng hoặc thông tin SMTP do nhà cung cấp cấp khi được yêu cầu.

Muốn xem OTP ngay trên máy thay vì gửi email thật, dùng [Mailpit local](docs/getting-started.md#email-local-với-mailpit). Cách này dùng cổng 1025 và cần tắt SMTP auth/STARTTLS theo hướng dẫn đó.

### 4. Cloudinary và kiểm duyệt AI

| Nhóm | Biến cần điền | Dùng cho |
| --- | --- | --- |
| Cloudinary | `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Lưu ảnh, video, avatar và tệp đính kèm. Ba giá trị thuộc cùng một tài khoản Cloudinary. |
| Dịch vụ kiểm duyệt | `MODERATION_ENABLED=true`, `MODERATION_SERVICE_URL=http://localhost:8000`, `MODERATION_API_TOKEN` | Backend gọi FastAPI. URL local dành cho Java chạy trên máy. |
| API AI | `OPENAI_API_KEY` | Khóa API thật dùng ở FastAPI; khác token nội bộ và không thay bằng chuỗi tự sinh. |
| Model | `OPENAI_MODERATION_MODEL`, `OPENAI_AUDIO_MODEL` | Tên model theo mẫu và khả năng truy cập của tài khoản API. |
| Nguồn media | `MODERATION_ALLOWED_MEDIA_HOSTS=res.cloudinary.com` | Host mà dịch vụ kiểm duyệt được tải media; cập nhật nếu môi trường sử dụng host CDN khác. |

Các biến timeout Java dùng đơn vị như `2s`, `10s`, `60s`; timeout Python có hậu tố `_SECONDS` dùng số giây như `8`, `15`. Mẫu đã có giá trị để bắt đầu, chỉ tăng khi đã xác định nguyên nhân quá hạn.

Giữ kiểm duyệt bật. Lỗi `401`, quota, `429` hoặc timeout cần kiểm tra khóa, quyền truy cập, kết nối và log; tắt kiểm duyệt không xác nhận rằng luồng kiểm duyệt đã hoạt động. `/healthz` chỉ xác nhận dịch vụ sẵn sàng, không chứng minh khóa API hay quota đã dùng được.

### 5. Redis và dịch vụ local

```dotenv
REDIS_URL=redis://localhost:6379
REDIS_CACHE_ENABLED=true
RATE_LIMIT_REDIS_REQUIRED=false
LOCAL_SERVICES_AUTO_START=true
LOCAL_SERVICES_STARTUP_TIMEOUT=5m
SPRING_DOCKER_COMPOSE_ENABLED=false
APP_WEBSOCKET_BROKER_RELAY_ENABLED=false
APP_CALLS_DISTRIBUTED_STATE_ENABLED=false
```

Mẫu dùng Redis local không có mật khẩu. Nếu Redis đã bật xác thực, đặt `REDIS_URL=redis://:MAT_KHAU@HOST:6379` với mật khẩu được mã hóa URL khi cần và tự quản lý dịch vụ đó. `REDIS_PASSWORD` trong mẫu dành cho Redis của Docker Compose; nó không tự thay mật khẩu Redis đang chạy trên máy.

Cơ chế tự bật dịch vụ hỗ trợ Redis loopback cổng 6379 không xác thực và moderation HTTP loopback cổng 8000. Đặt `LOCAL_SERVICES_AUTO_START=false` nếu đã tự quản lý dịch vụ. Cờ này chỉ tắt tự khởi động; không thay thế dịch vụ mà chức năng đang cần. Dừng backend không tự dừng Redis/moderation.

Nếu chủ động chạy local không dùng Redis, xem profile [`no-redis`](docs/getting-started.md#các-profile). Khi triển khai nhiều backend, dùng Redis và broker chung theo cấu hình triển khai.

### 6. Firebase cho Google login và thông báo đẩy

Firebase là tùy chọn khi chạy Java local: có thể để nhóm biến `FIREBASE_*` trống nếu chưa dùng Google login/FCM. Để sử dụng, điền cấu hình của **cùng một Firebase project**:

- **Web SDK:** `FIREBASE_PROJECT_ID`, `FIREBASE_WEB_API_KEY`, `FIREBASE_WEB_AUTH_DOMAIN`, `FIREBASE_STORAGE_BUCKET`, `FIREBASE_MESSAGING_SENDER_ID`, `FIREBASE_WEB_APP_ID`.
- **Backend:** `FIREBASE_ADMIN_CREDENTIALS` trỏ tới file JSON service account có thật trên máy, hoặc dùng Application Default Credentials khi môi trường đã thiết lập.
- **Push:** thêm `FIREBASE_VAPID_KEY`, origin phù hợp và quyền nhận thông báo trên trình duyệt.

Bật nhà cung cấp Google và cấu hình domain đăng nhập phù hợp trong Firebase. JSON Admin chỉ nằm phía server, không đặt vào `static`. Riêng `docker-compose.yml` hiện tại yêu cầu đường dẫn JSON để mount vào container, kể cả khi chỉ muốn dựng cả stack.

### 7. Cuộc gọi qua LAN hoặc Tailscale

Một backend local dùng broker bộ nhớ, giữ hai cờ relay/distributed là `false`. Khi chưa có TURN, để trống `APP_WEBRTC_TURN_URLS` và `APP_WEBRTC_TURN_SHARED_SECRET`; STUN giữ theo mẫu. Nếu cần TURN, điền URL cả hai máy khách truy cập được và khóa khớp server TURN.

Để hai thiết bị dùng mic/camera qua IP, chạy backend bằng HTTPS được tin cậy. Ví dụ trên Windows, với IP Tailscale thực sự được gán cho máy chủ:

```powershell
.\setup-lan-call.ps1 -LanIp 100.117.88.61 -Port 8085 -TrustCertificate
```

Dừng backend đang chiếm cổng trước khi chạy helper. Hai máy mở cùng `https://100.117.88.61:8085`; máy khách cần tin cậy chứng chỉ công khai `.cer` do máy chủ tạo. Chỉ chia sẻ `.cer`, giữ riêng `.p12` và mật khẩu. Helper đặt origin/cookie theo HTTPS cho phiên chạy; xem [hướng dẫn đầy đủ](docs/getting-started.md#gọi-thoại-và-video-qua-lan-hoặc-tailscale) về chứng chỉ, mạng và TURN.

## Chạy bằng Docker Compose

Stack gồm app, Nginx, Redis, moderation, RabbitMQ và Coturn. **PostgreSQL cần chuẩn bị riêng**; container dùng `COMPOSE_DB_URL` để truy cập database, không dùng `localhost` để trỏ về máy host.

Cả cách chạy trực tiếp và stack Docker đều dùng cổng **8085**. Dừng backend chạy trực tiếp trước khi mở stack Docker để tránh trùng cổng.

Ngoài các khóa/dịch vụ phía trên, điền `REDIS_PASSWORD`, `RABBITMQ_PASSWORD`, `TURN_SHARED_SECRET`, `FIREBASE_ADMIN_CREDENTIALS` và địa chỉ TURN. Compose dùng `TURN_SHARED_SECRET` cho cả Java lẫn Coturn; không dùng lại JWT secret.

Ví dụ các giá trị cần đổi khi thử stack bằng HTTP trên chính máy:

```dotenv
COMPOSE_DB_URL=jdbc:postgresql://host.docker.internal:5432/aloute
FRONTEND_BASE_URL=http://localhost:8085
APP_CORS_ALLOWED_ORIGINS=http://localhost:8085
APP_SECURITY_COOKIE_SECURE=false
APP_TRUSTED_PROXY_CIDRS=172.29.0.1/32,127.0.0.1/32,::1/128
APP_REPLICAS=2
```

CIDR trên cần đối chiếu mạng/proxy thực tế. Khi truy cập từ máy khác, thay `TURN_PUBLIC_HOST`, `TURN_EXTERNAL_IP` và `APP_WEBRTC_TURN_URLS` bằng địa chỉ mà các trình duyệt truy cập được. Mẫu `localhost` chỉ dành cho chính máy đó.

```powershell
docker compose --env-file .env config --quiet
docker compose --env-file .env up -d --build
docker compose ps
```

`config --quiet` kiểm tra cấu hình Compose, chưa xác nhận được kết nối database, thông tin xác thực hay khả năng gọi các dịch vụ bên ngoài.

Compose tự chọn profile `prod`, giữ app ở cổng nội bộ 8080, bật relay/distributed và ghi đè URL Redis/moderation sang dịch vụ nội bộ. Nginx mở **[http://localhost:8085](http://localhost:8085)** ở loopback; cấu hình này chưa cấp HTTPS công khai. Triển khai thật cần proxy/chứng chỉ HTTPS, `APP_SECURITY_COOKIE_SECURE=true` và URL/origin theo domain thực tế; xem [hướng dẫn triển khai](docs/getting-started.md#chạy-toàn-bộ-hệ-thống-bằng-docker-compose).

## Kiểm tra sau khi chạy

Với cấu hình local mặc định:

```powershell
Invoke-RestMethod http://localhost:8085/actuator/health
Invoke-RestMethod http://localhost:8000/healthz
```

Thử đăng ký/nhận OTP, đăng nhập, đăng bài, upload, nhắn tin giữa hai tài khoản và gọi trên hai thiết bị. Google login và push cần thử riêng khi đã cấu hình Firebase.

| Hiện tượng | Kiểm tra trước |
| --- | --- |
| Không kết nối database hoặc Flyway lỗi | Database đã tạo, JDBC URL/tài khoản/schema, quyền migration và lịch sử Flyway. |
| `Cannot start local Moderation` | Docker/WSL, khóa API, token nội bộ và [log dịch vụ local](moderation/README_LOCAL_VI.md). |
| Đăng nhập xong lại về login | HTTP/HTTPS, cookie secure, URL/origin và biến môi trường đang ghi đè `.env`. |
| Không nhận OTP | SMTP host/cổng/auth/STARTTLS, địa chỉ gửi và hộp thư rác hoặc Mailpit. |
| Không kết nối RabbitMQ khi chạy local | Hai cờ relay/distributed phải là `false` nếu chưa triển khai broker chung. |
| Có chuông nhưng thiếu âm thanh/hình | HTTPS, quyền mic/camera, ICE, TURN và tường lửa; kết nối WebSocket chỉ xác nhận phần báo hiệu. |

Tệp chat qua `/api/upload/file` tối đa **1 MB/tệp**; ảnh qua `/api/upload/image` tối đa **15 MB/ảnh**. Trần media/multipart mặc định **50 MB/tệp**, **52 MB/request** không thay thế giới hạn riêng của từng endpoint.

## Tài liệu

- [Hướng dẫn đầy đủ](docs/getting-started.md): kiến trúc, cấu hình, phân quyền, kiểm thử và vận hành.
- [Ranh giới phân hệ](docs/architecture/module-boundaries.md): tổ chức backend và phụ thuộc giữa các module.
- [Redis và moderation local](moderation/README_LOCAL_VI.md): Docker, Windows/WSL và vị trí log.
- [Kiểm thử](docs/getting-started.md#kiểm-thử) và [kiểm thử tải](deploy/load/README.md).

## Nhóm thực hiện

| Thành viên | Phụ trách chính |
| --- | --- |
| Phạm Quý Vỹ | Xác thực, quản lý tài khoản, hồ sơ cá nhân và bài viết. |
| Bùi Phạm Đức Thắng | Bảng tin, tìm kiếm, quan hệ xã hội, tương tác bài viết và thông báo. |
| Trần Thị Phương Trang | Nhắn tin, cuộc gọi, nhóm trò chuyện và kiểm duyệt nội dung. |
