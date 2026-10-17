# ALOUTE — backend local/Eclipse

## Yêu cầu

- JDK 21 và Eclipse IDE for Enterprise Java and Web Developers (hoặc Eclipse có Spring Tools).
- Maven Wrapper đi kèm dự án.
- PostgreSQL (local hoặc connection riêng), MongoDB (local hoặc Atlas), Redis local tại `localhost:6379`.
- Cloudinary/Firebase/SMTP và moderation service nếu kiểm thử các tính năng tương ứng.

## Thiết lập Eclipse

1. Sao lưu dự án và `.env` hiện tại. Không đưa `.env`, thư mục `.secret`, Firebase Admin JSON, `target` hoặc `.git` vào zip/chia sẻ.
2. Sao chép `.env.example` thành `.env`, thay placeholder bằng giá trị local. `REDIS_URL=redis://localhost:6379` là cấu hình cho Redis chỉ bind localhost không có `requirepass`. Nếu Redis đã bật mật khẩu, dùng URL có auth khớp cấu hình server. Không đổi khóa mã hóa TOTP trực tiếp khi database còn dữ liệu được mã hóa bằng khóa cũ.
3. Trong Eclipse, đặt Installed JRE/JDK và Java Compiler là 21, working directory là thư mục gốc dự án; nhấp phải project → Maven → Update Project.
4. Khởi động Redis trước khi chạy `com.aloute.AlouteApplication` bằng Run As → Spring Boot App. Profile mặc định không cần `prod`; Redis cache và rate limiter bật mặc định.
5. Kiểm tra `http://localhost:8080/`. Nếu gặp lỗi Redis, kiểm tra `REDIS_URL`, cổng `127.0.0.1:6379` và kiểu xác thực của server.

## Redis local không dùng Compose

Nếu chưa có Redis local, có thể chạy riêng Redis mà không build ứng dụng/Compose:

```powershell
docker run --name aloute-redis-local -p 127.0.0.1:6379:6379 -d redis:7-alpine
docker exec -it aloute-redis-local redis-cli ping
```

Kết quả cần là `PONG`. Chỉ sử dụng Redis không xác thực khi port bị giới hạn ở loopback; không publish Redis này ra mạng công khai. Nếu container cùng tên đã tồn tại, kiểm tra và start container có sẵn thay vì tạo trùng tên.

## Build và kiểm thử

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd verify
```

Kiểm tra schema trên PostgreSQL tạm hoàn toàn mới (yêu cầu Docker Desktop):

```powershell
.\deploy\scripts\validate-empty-schema.ps1
```

Maven Failsafe chạy `FreshPostgresSchemaValidationIT` trong `verify`. Test phải dùng database kiểm thử riêng; không trỏ script hoặc migration test sang Supabase.

## Upload, kiểm duyệt và lifecycle media

Upload dùng một enum `UploadPurpose` và tách thư mục Cloudinary `aloute/post`, `aloute/chat`, `aloute/community`, `aloute/avatar`, `aloute/general`. Backend chấp nhận URL quản lý chỉ khi HTTPS, đúng hostname Cloudinary, đúng `CLOUDINARY_CLOUD_NAME`, đúng purpose folder, URL nguyên bản đã được ghi vào Mongo ledger và `uploaderId` khớp người dùng đang thao tác. Các URL transformed hoặc URL từ cloud khác bị từ chối. Cả `/api/upload/*` lẫn `/api/users/me/avatar` chịu giới hạn 30 upload/user/giờ qua Redis (vượt hạn sẽ bị chặn 15 phút); khi `RATE_LIMIT_REDIS_REQUIRED=true`, Redis hỏng làm upload bị từ chối thay vì bỏ qua limit.

`purpose=post` chỉ đăng ký media trong request; worker kiểm duyệt bài sau khi lưu ở trạng thái `PENDING`. Chat/community/avatar/general image/video phải được kiểm duyệt trước khi endpoint trả URL nếu `MODERATION_ENABLED=true`; upload-time timeout mặc định `12s`. Nếu muốn kiểm thử moderation thật, chạy Python service ở `http://localhost:8000`, cấu hình cùng `MODERATION_API_TOKEN` ở cả backend và service, cùng OpenAI API key hợp lệ. `MODERATION_ENABLED=false` là bypass cho demo/local, không phải cấu hình production.

Tệp raw/document chưa được hỗ trợ: cả controller và storage đều từ chối `DetectedType.FILE` cho tới khi có scanner riêng. Không nên quảng bá PDF/Office/audio/raw như loại upload được hỗ trợ.

`MediaAssetLifecycleService` là nơi duy nhất quản lý attach/xóa/tombstone/retry: bài HIDDEN, bài bị xóa, thay avatar/community image, rời cộng đồng khi là thành viên cuối cùng, xóa cộng đồng/tài khoản và xóa tin nhắn chat cho mọi người đều kiểm tra tham chiếu trước khi xóa media. Asset UPLOADED bỏ mồ côi được dọn sau 2 giờ; `ATTACHED` không bị quét lặp vô hạn. Admin APPROVE bị chặn nếu media đã xóa hoặc đang chờ xóa, tránh duyệt lại bài bị mất ảnh.

## Redis cache và presence

`REDIS_CACHE_ENABLED=true` bật Redis cache cho feed/trending. Redis cache lỗi sẽ fail-open (cache chỉ là tối ưu); rate limit được xử lý riêng và có thể fail-closed khi `RATE_LIMIT_REDIS_REQUIRED=true`. Online presence lưu trong Redis bằng lease per WebSocket session; cấu hình `PRESENCE_REFRESH_INTERVAL_MS` phải nhỏ hơn một nửa `PRESENCE_CONNECTION_TTL_MS`. Khi Redis không khả dụng, chỉ trạng thái phiên tại node hiện tại còn đáng tin cậy. Cần RabbitMQ/STOMP relay để fan-out sự kiện qua nhiều instance.

## OpenAPI và WebRTC

`OPENAPI_ENABLED=false` theo mặc định. Để kiểm tra local, bật `OPENAPI_ENABLED=true`, restart rồi mở `/swagger-ui.html` hoặc `/v3/api-docs` với tài khoản `ROLE_ADMIN`.

`APP_WEBRTC_STUN_URLS` cấu hình STUN; `APP_WEBRTC_TURN_URLS` và `APP_WEBRTC_TURN_SHARED_SECRET` chỉ tạo thông tin TURN REST ngắn hạn khi đã có server TURN thật. Backend không tự triển khai Coturn. Cho tới khi nhóm dựng TURN và kiểm tra từ các mạng khác nhau, một số NAT/firewall vẫn có thể chặn cuộc gọi.

## Database và bảo mật

Flyway là nguồn thay đổi schema; Hibernate dùng `validate` cho database dùng chung. Backup PostgreSQL/MongoDB trước migration hoặc chuyển encryption key. Xoay secrets nếu từng được chia sẻ; không commit `.env` hoặc Firebase Admin JSON.
