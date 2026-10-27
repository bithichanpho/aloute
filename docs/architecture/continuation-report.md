# Tiếp tục refactor — lượt 2, 24/10/2026

## Kết quả và phạm vi

**Implemented, Tested bằng unit/architecture/wiring; toàn bộ chương trình vẫn PARTIAL.** Bỏ qua việc thiếu Git metadata theo yêu cầu; không coi đó là blocker. Không ghi đè các thay đổi lượt 1. Snapshot đầu lượt 2: `target/architecture-continuation-initial-hashes.json`; source gốc của các file sửa: `target/architecture-continuation-original`.

Source hiện tại có **320 Java production files và 96 test classes**. Lượt này thêm 24 production files, 10 test classes; không thêm database/broker/dependency runtime, không move hàng loạt entity, không migration dữ liệu.

## 1. Posts sở hữu edit/history và Saved Posts

- `PostEditingService` nhận immutable `PostEditCommand`, áp dụng lại chính sách tác giả, block/private/community qua owner-internal PostAccess, giữ CAS trên updatedAt/content/non-HIDDEN, revision limit 100 và moderation transitions hiện có.
- `SavedPostsService` sở hữu SavedPost writes và query page 20; card assembly/visibility đi qua PostCards theo một batch. Unsave vẫn được phép bỏ tham chiếu của chính viewer khi post không còn đọc được.
- Media attachment, rate limit và moderation policy/dispatch sử dụng public APIs có implementation thực tế trong các service đang chạy. Không lộ entity qua public posts.api.
- `SocialFeatureService` giữ các chữ ký Java và response facade cũ; edit/history/save/saved chỉ ủy quyền. History copies giữ đúng JSON fields/values, có test so sánh serialization. `SocialFeatureController` không đổi URL/method/request/response.
- Tag parser chuyển về Posts domain, giữ static ContentTagService facade. Feed cache implementation chuyển về Feed infrastructure, giữ FeedCacheInvalidator bean/constructor và các cache keys/SCAN/batch policy.

### Confirmed Bug và regression

Trước sửa, visibility-only edit PUBLIC → ONLY_ME chỉ evict cache tác giả; Caffeine feed của viewer khác vẫn giữ card cũ. `PostVisibilityCacheRegressionTest` đã chạy **FAIL trước sửa**, rồi **PASS sau sửa**; test dùng cache thật và update-store double.

PostEditing phát `PostChanged` version 1 gồm eventId/type/occurredAt/aggregateId/actorId/invalidateAll, không chứa body/media. Feed xử lý qua listener. Privacy change hoặc chuyển PENDING đều evict toàn feed; visibility-only edit không đổi moderation verdict. Đây là **in-process best-effort cache hint**, không phải durable event/outbox. Hook chờ outer transaction commit; rollback không phát event/dispatch review. Không có outer transaction thì atomic PostgreSQL update đã kết thúc trước hint.

**Giới hạn:** crash/Redis invalidation failure vẫn dùng TTL như cơ chế cũ. Chưa có read-side revalidation trên mọi cache hit, vì vậy chưa tuyên bố loại bỏ mọi stale-private-content race khi cache/distributed infrastructure lỗi. Cần tiếp tục xác minh và xử lý riêng đường này.

## 2. Users / Identity / Administration

- `UserAccountAdministration` trả immutable ChangedAccount/AccountReview và tự kiểm tra actor active ADMIN tại backend. Cấm tự khóa hoặc sửa ADMIN/HOST được bảo vệ; HOST không có quyền ADMIN. Account review không xuất email/password/token.
- `JpaUserAccountAdministration` sở hữu User repository/state changes; giữ active/selfDeactivated/revocation timestamp semantics cũ.
- `SessionInvalidation` giữ refresh-token persistence trong Identity; adapter gọi revocation hiện có và invalidates authentication cache sau commit.
- `AccountModerationService` là application orchestrator: User state và Identity revocation nằm trong một PostgreSQL transaction; không giả lập giao dịch phân tán.
- `SocialFeatureService` không gọi User repository hoặc private RefreshTokenService/AuthenticationUserCache nữa ở logic đang chạy. Legacy constructors chỉ làm compatibility composition. Block dùng UserDirectory.exists; role/review/state changes dùng public owner APIs. Report resolution/audit payload và cache action sau moderation giữ nguyên.

Unit tests phủ ADMIN/HOST/inactive actor/self/protected target, projection privacy, failure propagation, Spring transaction commit/rollback interceptor và existing report-audit behavior. **Chưa verify rollback SQL thật** hoặc full application security chain với DB.

## 3. Notification reference cleanup

Owner cleanup vẫn nhận bounded batches tối đa 500 IDs. Các legacy long-returning reference-delete methods nay là default adapters đến int-returning bulk JPQL commands; không tải từng entity để xóa. Giữ type filters của community và null-reference semantics ở typed command; không mở rộng phạm vi authorized deletion.

`NotificationBulkCleanupContractTest` dùng DefaultMethodInvokingMethodInterceptor của Spring Data thật để kiểm tra chữ ký/long result và một bulk command mỗi lời gọi. Không coi số method calls này là SQL benchmark.

Thêm `NotificationBulkCleanupIT` opt-in: chỉ disposable local PostgreSQL, validate JPQL qua repository factory, chạy các lệnh với absent reference trong transaction và rollback. **Đã compile và SKIPPED vì thiếu DB test**, không báo PASS cho SQL. Unused legacy single-reference delete vẫn chưa tối ưu; không thay schema/entity callbacks/cascades.

## 4. Architecture gates

ModuleBoundaryTest hiện kiểm tra 11 rules: public APIs không xuất persistence; Posts application chỉ dùng owner models/platform persistence và public collaborators; administration chỉ dùng User/Identity APIs; Social facade không gọi User repository/private identity; Feed business code không gọi internals; Feed cache infrastructure chỉ dùng owner/framework (gồm Caffeine đã có); no cycles giữa các packages đã migrate. Không có suppress toàn legacy graph.

Trong lượt test đầu, rule Feed cũ coi Caffeine.asMap là forbidden dependency khi cache code vừa chuyển vào owner package. Rule được phân tách rõ application/API và infrastructure, vẫn chặn foreign business internals; không nới cho phép truy cập repository/service module khác.

## 5. Lệnh và kết quả thực tế

| Gate | Kết quả | Log |
|---|---|---|
| Baseline `mvn -B test` | 327 total; 3 failures + 245 errors do Mockito self-attach failure, 1 skip; chưa sửa business source | architecture-continuation-baseline.log |
| Baseline sau explicit Mockito agent | **326 pass, 0 fail/error, 1 skip** | architecture-continuation-baseline-agent.log |
| Privacy regression trước sửa | **1 failure dự kiến**, cache viewer khác còn card | architecture-post-visibility-red.log |
| Initial Posts slice | **49 pass** | architecture-continuation-posts.log |
| Expanded gate | Một lần JVM native-memory failure; một lần C hết chỗ không tạo được temp; chưa chạy assertions | architecture-continuation-posts-gate.log, architecture-continuation-posts-gate-limited.log |
| Expanded gate sau temp workaround | 48 pass, 1 architecture failure về Caffeine scope; đã sửa rule theo layer | architecture-continuation-posts-gate-temp.log |
| Owner gate | Một compile error ở getter Boolean trong test mới; sửa đúng getSelfDeactivated theo source; sau đó **68 pass** | architecture-continuation-owner-gate.log, architecture-continuation-owner-gate-rerun.log |
| Full verify sau toàn bộ production changes | **357 pass, 0 fail/error, 1 context skip**; FreshPostgresSchemaValidationIT **skip**; JAR built | architecture-continuation-verify.log |
| Targeted verify cho bulk contract + integration mới | **2 pass, 0 fail/error**; NotificationBulkCleanupIT **skip**, không có DB test | architecture-continuation-bulk-it.log |

Test cuối chỉ thêm integration test, không thay production source sau full verify. Tất cả 96 test classes đã compile. Chưa chạy load test, real DB rollback, Redis/RabbitMQ multi-instance, Firebase/Cloudinary/TURN.

### Workaround môi trường đã dùng

C hết chỗ trống và JVM native memory thiếu. Không xóa file/tắt tiến trình của người dùng, không thay runtime/deployment config. Maven chạy offline với dependencies đã cache, TEMP/TMP và java.io.tmpdir trỏ target/test-temp trên D; giới hạn heap/CPU chỉ ở tiến trình build/test. pom.xml sửa tối thiểu để Surefire/Failsafe nạp Mockito agent rõ ràng, không đổi Mockito version hoặc bỏ tests; giữ late-bound argLine cho coverage profile (coverage profile chưa chạy).

Ví dụ lệnh full gate thực tế (đường dẫn cố định của workspace này):

```powershell
New-Item -ItemType Directory -Force -Path target/test-temp | Out-Null
$env:TEMP='D:\aloute-develop123\aloute-develop123\target\test-temp'
$env:TMP=$env:TEMP
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-develop123/target/test-temp'
mvn -o -B '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-develop123/target/test-temp' verify
```

Gate chọn lọc dùng cùng environment/argLine và `-Dtest=...`; targeted integration cuối dùng `-Dtest=NotificationBulkCleanupContractTest -Dit.test=NotificationBulkCleanupIT verify`. Không in/read .env hoặc service secrets.

## 6. Compatibility, rollback và phần còn lại

DTO/model/document/resources đã hash không đổi so với đầu lượt; không sửa REST controller trong lượt 2, không đổi STOMP/WebRTC/GraphQL, schema/collection keys hoặc cache key format. Không xóa dữ liệu thật hoặc bật physical media deletion. Không push/merge/deploy.

Patch riêng lượt 2: `target/architecture-continuation.patch`; cumulative patch: `target/architecture-refactor.patch`. Giữ source snapshots/patch trước Maven clean. Không tự động rollback/copy đè thay đổi mới.

P1 còn lại: missed-call Redis claim → durable PostgreSQL recovery; stale cache khi invalidation/crash thất bại; legacy Post/Comment moderation/report ownership; full identity/users/messaging/media/admin migration; real transaction/concurrency/infrastructure tests. Performance p95/throughput/EXPLAIN: **NOT VERIFIED**. Không tuyên bố toàn bộ chương trình DONE.


## Tiếp nối lượt 3

Các số liệu trên là checkpoint cuối lượt 2. Lượt 3 đã xử lý stale cached cursor Feed cards khi eviction thất bại và chuyển Calls state machine sang messaging.calls, sửa WebSocket send failure chặn history/intent. Xem [cache-and-calls-report.md](cache-and-calls-report.md) và báo cáo tổng mới nhất; Redis claim → PostgreSQL crash gap vẫn chưa được xử lý.
