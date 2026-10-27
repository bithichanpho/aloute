# Lượt 3 — Feed cache authorization và Calls ownership

Trạng thái: **Implemented / Partially Tested**; tổng chương trình vẫn PARTIAL. Tiếp tục trên source mới nhất sau lượt 2, không reset/ghi đè baseline. Workspace không có Git metadata; user yêu cầu tiếp tục và bỏ qua `.git`.

## Phase 5 — cache không được thay thế authorization

Confirmed Bug bằng test RED: viewer đọc card PUBLIC, bài đổi ONLY_ME nhưng cache invalidation không đến; lần đọc sau vẫn trả nội dung cũ. `architecture-cache-read-red.log`: 1 failure dự kiến.

- `FeedQueryService` là facade luôn chạy. `FeedPageQuery` giữ nguyên traversal/keyset, giới hạn batch, codec và Cacheable keys. Cache values vẫn FeedCursorPage, cùng serialVersionUID/DTO và namespace feedPages.
- Query body đánh dấu AtomicBoolean riêng từng request. Body chạy nghĩa là cache miss và đã dùng quyền hiện tại; body không chạy nghĩa là cache hit, facade gọi `FeedPostReader.refreshCards` qua public API. Không dựa vào một lần kiểm tra cache trước proxy vì có race cache population. Không thêm lần hydrate cho miss.
- Posts reload tối đa 30 IDs trong một truy vấn có limit, áp dụng AccessBatch hiện hữu và bulk mapper, nạp lại body/nested original/current user interactions, khôi phục thứ tự IDs cache. Bài thiếu/mất quyền bị loại; cursor/hasMore giữ nguyên kể cả trang sparse. Không rerank/refill timeline.
- `PostService` bỏ hai Cacheable wrappers để callers legacy cũng đi qua authorization facade. Không fallback card cũ khi DB/policy lỗi. Cache invalidation sau commit vẫn chỉ best-effort optimization.
- Không đổi schema/resources/controller Feed. Không tuyên bố mọi timeline selection/filter legacy đã được migrate; bảo đảm này áp dụng cursor Feed của public FeedQuery và facade liên quan. Các read caches khác vẫn cần audit.

Gate GREEN ban đầu: 28 pass; gate mở rộng: **42 pass**, `architecture-cache-read-gate.log`. Cases: ONLY_ME, private author, block, community membership revoked, original private, deleted/hidden/inactive, fresh quote + original, DB fail closed, order, cursor, bounded batch, miss không hydrate hai lần, viewer keys, Spring cache wiring, REST và ArchUnit. Dùng Caffeine thật, Posts adapter + mapper + AccessBatch thật, persistence test doubles; không coi là integration PostgreSQL/Redis.

## Phase 6 — Calls vertical slice

Confirmed Bug bằng test RED: WebSocket timeout throw làm mất bước lưu lịch sử và enqueue notification của lần chạy đó. `architecture-call-delivery-red.log`: 1 failure dự kiến.

- State machine/Redis scripts/local synchronization chuyển một class sang `messaging.calls.application.CallRegistryService`, là primary implementation của `messaging.api.CallSignaling`.
- ChatController dùng public Session view; giữ MessageMapping, JSON signal, destination /queue/call và principal-derived identity. Legacy `CallTimeoutService` vẫn là scheduling/Java facade, giữ constructors, CallInfo record và hàm cũ; cả facade và public provider dùng một registry trong Spring.
- Users sở hữu `CallParticipants`/`JpaCallParticipants`. Calls nhận immutable profile projection đúng các trường signal trước đây (id, username, fullName, avatarUrl), không entity/password/email/token. Batch lookup hai người dùng, SQL IN chunks tối đa 500; không thêm query/schema.
- Gửi timeout signal được catch riêng cho từng người nhận; lỗi broker không chặn người nhận còn lại, history API hoặc Notifications intent. Không swallow history/intent failure: vẫn log như trước. Không có cam kết exactly-once hoặc phục hồi durable mới.
- Giữ nguyên cả 4 Lua scripts bằng so sánh source trước/sau, Redis names/TTL/timeout và encoded fields; không thay transition/claim semantics. Scheduled poll duy nhất vẫn trên compatibility facade.

Gate Calls: **26 pass**, `architecture-calls-gate.log`: 12 scoped architecture rules, local answer/timeout concurrency, duplicate timeout, stale answer, double hangup, participant rejection, actual Spring primary/facade shared-state wiring, Users projection, STOMP và scheduled-lock checks. Bổ sung 3 controller contract/security cases sau gate, được đưa vào full verify.

## Phase 9–11 — kiểm chứng, dữ liệu, rollback

Source hiện tại: 325 production Java files, 104 test classes. Đối chiếu baseline đầu phiên: 203 DTO/model/resource hashes không đổi; không migration, không xóa dữ liệu, không thay UI/frontend/framework/infrastructure. Không đọc secrets. Không push/deploy.

Full verification: **383 unit pass, 0 failures/errors, 1 context skip; 2 SQL/schema IT skips; BUILD SUCCESS và executable JAR built**. Log cuối: `target/architecture-cache-calls-verify-rerun.log`. Lần full gate đầu (`architecture-cache-calls-verify.log`): 382 pass, 1 failure do expected fields trong test mới bỏ sót candidate của fixture, 1 skip. Controller cũ đã forward candidate; chỉ sửa assertion theo contract/source và rerun toàn gate. Dùng đúng memory caps/TEMP trên D/offline dependencies như lệnh ở continuation-report.md, với `verify`; gate chọn lọc thay bằng `-Dtest=... test`. Mockito explicit agent vẫn được giữ.

Fresh-output verify cuối: **383 pass, 0 fail/error, 1 unit skip + 2 IT skips**, `architecture-cache-calls-fresh-verify.log`. Đã kiểm tra incremental JAR còn inner class ở vị trí cũ, chuyển hai generated output directories trong workspace sang checkpoints, build/test từ đầu; JAR mới không còn CallTimeoutService$LocalCall cũ và có các owner classes mới. Không Maven clean làm mất snapshots.

Docker daemon được kiểm tra lại và vẫn không kết nối được pipe Docker Desktop Linux engine. PostgreSQL/Redis/RabbitMQ/TURN/storage integration, Redis concurrency nhiều instance, crash recovery và throughput/p95/EXPLAIN: **NOT VERIFIED**. Việc C hết dung lượng là lỗi môi trường lịch sử lượt 2; kiểm tra mới đã có chỗ trống, không dùng làm blocker hiện tại.

Review/rollback: `target/architecture-cache-calls.patch` cho lượt 3; `target/architecture-continuation.patch` từ đầu lượt 2; `target/architecture-refactor.patch` cộng dồn. Originals từng checkpoint đã kiểm SHA trước tạo patch. Lưu ngoài target hoặc đưa vào version control trước Maven clean; không tự động copy rollback đè thay đổi mới.

## Remaining issues

- P1: Redis timeout claim xóa state trước PostgreSQL history/outbox; process crash hoặc history/enqueue lỗi vẫn có thể mất side effects. Cần durable recovery thiết kế và integration riêng, không tuyên bố đã sửa bằng isolation WebSocket.
- P1: SocialFeature Post/Comment moderation/report, social writes, identity/user write use cases, community-post cleanup và phần lớn messaging/media/admin còn legacy ownership. Scoped architecture pass không chứng minh toàn backend hết cycle.
- P1: storage attach-vs-cleanup, real SQL rollback/JPQL và outbox retry/crash cần DB/storage test environment. Physical deletion vẫn default-off.
- P2: Feed cache hit nay có thêm bounded database authorization/card reads. Đây là đánh đổi privacy có chủ đích; chưa đo latency/throughput hay query plans, không tuyên bố cải thiện hiệu năng. Cache values không được viết lại sau mỗi hit nên quyền vẫn phải kiểm tra mỗi lần.
