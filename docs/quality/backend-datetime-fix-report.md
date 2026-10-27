# Sửa HTTP 500 `/feed` do ngày giờ JSONB

## 1. Verified Architecture Snapshot

Phạm vi là lỗi ngày giờ persistence, không phải hoàn tất toàn bộ chương trình refactor kiến trúc.
Backend Spring Boot 4.1.1, Java target 21; Maven chạy trên JDK 22 ở máy hiện tại.
JPA và JDBC JSONB cùng PostgreSQL; Redis cho các khả năng hiện có, REST/MVC và STOMP/WebRTC giữ nguyên.
Không có GraphQL trong source hiện tại. `.git` không tồn tại; đã giữ các thay đổi có sẵn và lưu checkpoint SHA-256 bên ngoài repository.

**Confirmed Bug:** `Jackson2ObjectMapperConfig` cung cấp `new ObjectMapper().findAndRegisterModules()`.
Jackson 2 mặc định bật `WRITE_DATES_AS_TIMESTAMPS`: `LocalDateTime` thành mảng 5–7 phần tử,
`Instant` thành số epoch seconds với phần lẻ nanosecond. `PostgresDocumentStore` copy mapper, tắt annotations
nhưng chưa tắt ghi timestamp. Mọi đường `save/insert/insertIfAbsent/saveAll` và atomic `SET/PUSH/ADD_TO_SET`
đều dùng mapper này. `SqlCriteriaCompiler` lại ép chuỗi `->>` sang `timestamp/timestamptz` ở cả sort và filter.
PostgreSQL không đọc được chuỗi mảng; kể cả ISO thì timestamp PostgreSQL chỉ giữ microsecond,
có thể làm sai equality/keyset khi hai bài chênh 1 nanosecond.

**Performance Risk:** các index V20261102 dùng biểu thức text; không khớp biểu thức sort/filter kiểu timestamp trước đây,
và text array/ISO trộn lẫn cũng không sắp thời gian đúng.
**Already Correct:** Feed có giới hạn truy vấn, cursor gồm thời gian + ID, batch authorization và kiểm tra lại cache.
Không thay thuật toán, codec cursor, endpoint, DTO hoặc WebSocket.

Kiểm kê toàn bộ temporal fields trong `model/document` (25 trường top-level, thêm một đường embedded):

| Collection | LocalDateTime: mảng cũ hoặc ISO | Instant: số epoch seconds cũ hoặc ISO có offset |
|---|---|---|
| posts | createdAt, updatedAt, moderationLeaseUntil, moderationLastAttemptAt, mediaCleanupQueuedAt | editHistory[].createdAt |
| comments | createdAt, moderationLeaseUntil, moderationLastAttemptAt | — |
| chat_messages | sentAt, deliveredAt | — |
| likes, reposts | createdAt | — |
| post_media_assets | uploadedAt, updatedAt, deletedAt | — |
| account_cleanup_jobs | — | createdAt, nextAttemptAt |
| account_security | — | pendingExpiresAt |
| comment_reactions, follow_requests, post_revisions, saved_posts, user_blocks | — | createdAt |
| content_reports | — | createdAt, reviewedAt |

Không tìm thấy LocalDate/LocalTime/OffsetDateTime/ZonedDateTime trong document models. Ngày giờ JPA native columns
(users, refresh tokens, notifications/outbox, communities, call_sessions…) không dùng JSONB compiler, không sửa schema/semantics.
Không có cấu hình timestamp-milliseconds trong mapper hiện tại: số Instant cũ được diễn giải là **seconds**, không đoán milliseconds.

## 2. Module Migration Status

| Module | Thay đổi trong lần sửa này | Trạng thái |
|---|---|---|
| Persistence | ISO writer riêng; parser SQL tương thích nhiều định dạng, so sánh chính xác nanosecond | Implemented |
| Posts/Feed | Chỉ truy vấn bài có createdAt hợp lệ ở cả SQL miss và cache refresh | Implemented |
| Messaging, Moderation, Media, Social, Identity cleanup | Dùng chung compiler đã sửa; không thay use cases | Implementation chung, regression kiểm tra qua verify |
| Identity/Users/Communities/Notifications/Administration native JPA | Không thay mã nghiệp vụ | Giữ nguyên |

## 3. Dependency / Architecture Violations

Không thêm dependency/library, vòng phụ thuộc, truy cập repository xuyên module hay infrastructure.
`PostFeedReader` tiếp tục là adapter thuộc Posts, sử dụng document-store API đã có.

## 4. Changed Files và thiết kế

Modified:

- `src/main/java/com/aloute/persistence/postgres/document/PostgresDocumentStore.java`: chỉ copy mapper persistence tắt WRITE_DATES_AS_TIMESTAMPS; query JSON dùng cùng mapper; fallback latest-per-field đi qua sort compiler.
- `src/main/java/com/aloute/persistence/postgres/document/SqlCriteriaCompiler.java`: sort và EQ/NE/IN/NIN/GT/GTE/LT/LTE dùng cùng khóa temporal; tham số bind cũng được parse cùng hàm.
- `src/main/java/com/aloute/persistence/postgres/document/Criteria.java`: thêm predicate `validDateTime()` cho temporal field, không thay public REST contract.
- `src/main/java/com/aloute/posts/infrastructure/PostFeedReader.java`: áp dụng predicate ở readBatch/refreshCards trước LIMIT và deserialize.
- `src/test/java/com/aloute/FullBackendContextIT.java`: thêm HTTP/MVC `/feed` với production composition, security, PostgreSQL, Redis test.

New:

- `src/main/resources/db/migration/V20261106__jsonb_temporal_query_keys.sql`.
- `src/test/java/com/aloute/persistence/postgres/document/DocumentDateTimeTest.java`.
- `src/test/java/com/aloute/persistence/postgres/document/DocumentDateTimeIT.java`.
- `src/test/java/com/aloute/persistence/postgres/document/DocumentDateTimeMigrationIT.java`.
- `docs/operations/jsonb-temporal-preflight.sql`, `docs/operations/jsonb-temporal-postflight.sql` và báo cáo này.

Không xóa file; không sửa migration cũ, model/DTO/API/schema/event/cursor/cache key.
Migration mới chỉ thêm hai hàm IMMUTABLE và 14 expression indexes tương ứng toàn bộ timestamp indexes cũ;
giữ nguyên index cũ cho compatibility. Không UPDATE/DELETE/backfill payload hay metadata.

`aloute_jsonb_local_datetime_key(jsonb)` trả integer[7] = năm/tháng/ngày/giờ/phút/giây/nanosecond,
kiểm tra lịch Gregorian và range java.time. So sánh tuple giữ cả 9 chữ số, không gắn múi giờ lên LocalDateTime.
`aloute_jsonb_instant_key(jsonb)` trả numeric epoch seconds chính xác, nhận số cũ và ISO offset,
đổi UTC bằng số học lịch, không đi qua timestamp microsecond; không phụ thuộc session timezone/DateStyle.
Hàm parse giá trị không hợp lệ thành SQL NULL; đây là xử lý input ở parser, không catch/nuốt exception từ truy vấn JDBC.

Null/missing/malformed `createdAt` không thể tạo cursor đúng: Feed loại chúng ở SQL, không đọc hết/lọc trong RAM,
không tự tạo ngày thay thế hay sửa dữ liệu. Audit postflight thống kê riêng malformed để owner sửa bằng nguồn dữ liệu đáng tin cậy.
Đọc document hỏng trực tiếp ngoài Feed vẫn strict: thay đổi này không biến dữ liệu hỏng ở mọi field thành null khi deserialize.

## 5. Security and Business Rules Preserved

Mapper API được giữ nguyên; regression kiểm tra injected mapper không bị mutation.
LocalDateTime round-trip giữ nanosecond, không UTC-convert; Instant giữ thời điểm UTC và phần lẻ.
Thứ tự createdAt DESC, ID DESC, giới hạn SQL, codec cursor và authorization batch giữ nguyên với dữ liệu hợp lệ.
Feed kiểm tra private/visibility bằng implementation hiện có, kể cả lượt cache hit.
Không thay block/mute/group policy, ranking/trending, chat ownership/unsend/call state, outbox delivery hay media deletion.
Tất cả dữ liệu chạy thử là synthetic trên PostgreSQL loopback `aloute_quality_*`; không đọc `.env` hay kết nối production.

## 6. Test Results

Log được lưu ở `D:/aloute-develop123/aloute-quality-checkpoints/20261024/datetime/`.

| Command/Test Suite | Passed | Failed | Skipped | Ghi chú |
|---|---:|---:|---:|---|
| Baseline `mvn -o -B … -Dtest=FeedQueryServiceTest -Dit.test=DocumentDateTimeIT verify` | 5 unit | 1 IT error | 0 | Tái hiện chính xác cast mảng sang timestamp (`baseline-red.log`) |
| Targeted verify ban đầu | 6 unit + 27 IT | 0 | 0 | HTTP 200 `/feed`, privacy/cache, mixed precision và migration fingerprint (`targeted.log`); sau đó bổ sung test biên |
| Final `mvn -B … verify` — Surefire | 387 | 0 | 1 | Tổng 388; `AlouteApplicationTests` cần DB_URL nên skip; full production context đã chạy riêng ở Failsafe |
| Final `mvn -B … verify` — Failsafe/PostgreSQL thật | 52 | 0 | 0 | BUILD SUCCESS; PostgreSQL 18.3, Redis 8.0.5; cả realtime browser smoke, call recovery, fresh schema và notification tests |
| preflight.sql + postflight.sql | 2 scripts | 0 | 0 | Chạy thật READ ONLY trên DB test; postflight liệt kê đủ 14 indexes |

Lệnh final thực tế (PowerShell, các biến môi trường test được đặt ở process scope):

```powershell
$env:ALOUTE_SCHEMA_VALIDATION_URL='jdbc:postgresql://127.0.0.1:55432/aloute_quality_datetime_20261024'
$env:ALOUTE_SCHEMA_VALIDATION_USERNAME='aloute_test'
$env:ALOUTE_SCHEMA_VALIDATION_PASSWORD='test-only'
$env:ALOUTE_REDIS_VALIDATION_PORT='56379'
$env:ALOUTE_BROWSER_EXECUTABLE='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$env:ALOUTE_PLAYWRIGHT_MODULE='C:/Users/buiph/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'
mvn -B '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/20261024/datetime/temp -Duser.timezone=UTC' verify
```

TEMP/TMP trỏ cùng thư mục temp trên D:, MAVEN_OPTS giới hạn heap 384m/metaspace 256m/code cache 64m/2 processors.
JVM test đặt UTC vì runtime PostgreSQL riêng thiếu alias Asia/Saigon; không đổi timezone production hay LocalDateTime semantics.
Kết quả được đối chiếu từ XML Surefire/Failsafe (`test-results.json`), không chỉ đọc thông báo Maven.
JAR cuối có migration V20261106 và PostgresDocumentStore đã sửa; test harness không nằm trong JAR.

Test mới bao gồm: array/ISO, mảng rút gọn, null/empty/malformed/invalid leap day/type/nanos;
1ns sorting và keyset, equality/range/IN/NIN, offset pagination, tất cả insert paths, atomic update/nested revision;
Instant numeric/offset/negative epoch, nhiều session timezone, lịch Gregorian và java.time extremes/random seed cố định.
Migration test tạo DB riêng ở V20261105, thêm dữ liệu hỗn hợp rồi nâng V20261106, so checksum payload + metadata và số dòng trước/sau,
kiểm tra 14 indexes, giữ index cũ và EXPLAIN khả năng dùng index mới không cần Sort (tắt seqscan trong transaction test).
HTTP test chạy production wiring và rendering template qua MockMvc/Spring Security, không phải request qua reverse proxy production.

## 7. Performance

Đã kiểm tra index expression khớp query bằng EXPLAIN trên dữ liệu test nhỏ.
Không đo throughput/p95 hay khẳng định tăng tốc. Chi phí ghi/khóa lúc tạo 14 index trên dataset lớn: **NOT VERIFIED**.
Không load test hoặc truy cập dataset production.

## 8. Migration, rollout, rollback và Remaining Issues

1. Chạy preflight trên snapshot/staging được duyệt; lưu count/format inventory và hash/metadata mỗi document nếu cần xác minh tuyệt đối.
2. Backup theo quy trình vận hành. V20261106 không cần backfill: array và ISO đọc song song; số Instant cũ tiếp tục đọc được.
3. Chọn maintenance window phù hợp: CREATE INDEX trong migration transaction có thể chặn ghi khi bảng lớn.
   Thử thời gian/lock trên bản sao dữ liệu trước; không khẳng định zero downtime.
4. Chạy Flyway bằng quy trình deploy hiện có **trước khi code mới phục vụ request**, rồi triển khai ứng dụng.
   Không sửa checksum/repair migration cũ và không chạy SQL chuyển toàn bộ JSON qua `::timestamp` (sẽ mất nanosecond).
5. Chạy lại preflight + postflight, so count/hash trên snapshot không có concurrent business writes; kiểm tra malformed và HTTP `/feed`/cursor/privacy.
   Hai script chỉ SELECT trong READ ONLY transaction; không in payload hay secret.
6. Không cần đổi hoặc flush toàn bộ Redis. Kiểm tra cache theo viewer/cursor hiện tại; read refresh vẫn kiểm tra lại visibility và valid date.
7. Rollback schema: giữ nguyên functions/indexes mở rộng là an toàn với code cũ; không drop chúng khi còn phiên bản mới chạy.
   Rollback về code cũ chưa có compatible reader sẽ tái xuất hiện lỗi với array và lỗi Instant numeric; ưu tiên forward fix,
   hoặc rollback thay đổi khác nhưng giữ bản vá persistence này. Không chuyển ISO ngược thành array và không xóa malformed rows.

P1 còn lại trên dữ liệu thực: số lượng malformed/nguồn sửa ngày chính xác và thời gian/lock tạo indexes chưa kiểm chứng.
P2: chỉ cân nhắc bỏ index text cũ sau khi toàn bộ instance đã nâng và có đo đạc; không thực hiện trong bản sửa này.
PostgreSQL test dùng bản 18.3 riêng trong WSL; staging cần xác nhận với version, extensions và dữ liệu triển khai thực tế.

## 9. Final Assessment

**DONE trong phạm vi sửa lỗi và kiểm chứng môi trường test:** đã code, tái hiện lỗi trước sửa, final verify thành công,
HTTP `/feed` trả 200 với mixed legacy data, 387 unit + 52 integration tests PASS, 1 unit test skip có lý do.
Production rollout/migration, kiểm kê dữ liệu thật và performance dataset lớn **NOT VERIFIED / NOT RUN**.
Toàn bộ chương trình modular-monolith refactor không được đánh dấu DONE bởi bản sửa hẹp này.
