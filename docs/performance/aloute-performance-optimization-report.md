# ALOUTE — Báo cáo tối ưu hiệu năng toàn stack

Ngày thực hiện: 24/10/2026. Phạm vi: source hiện tại sau bản vá datetime V20261106.
Trạng thái tổng thể: **PARTIALLY COMPLETE**; các lát cắt đã code được kiểm chứng riêng dưới đây.
Không suy diễn khả năng phục vụ production hoặc hàng nghìn concurrent users từ microbenchmark.

## 1. Kiến trúc và điểm nghẽn đã xác nhận

Spring Boot 4.1.1, target Java 21, Maven 3.9.11 chạy JDK 22. Một deployable modular monolith,
JPA + JDBC JSONB PostgreSQL, Redis, Thymeleaf/JavaScript, STOMP/WebRTC và các adapter ngoài hiện hữu.
Không thêm database/broker/framework/GraphQL. Workspace không có `.git`; dùng SHA checkpoint để phân biệt thay đổi,
không tạo lại Git hoặc ghi đè source trước phiên này.

| Luồng | Bằng chứng audit | Hành động/trạng thái |
|---|---|---|
| Following Feed | Mỗi nhóm 500 follows: một SELECT quan hệ + một SELECT posts, rồi merge Java; 5.000 following dùng 21 SQL/candidate batch | IMPLEMENTED / benchmark riêng |
| Exists JSONB | `count(query)>0` đọc toàn bộ số match kể cả chỉ cần boolean | IMPLEMENTED: SQL EXISTS, semantics pagination giữ nguyên |
| Prepared read plan | Generic plan không chứng minh được partial-index predicate khi collection_name là bind parameter | IMPLEMENTED: collection lấy từ annotation nội bộ đã validate, request values vẫn bind |
| Feed temporal | Writer ISO và parser tương thích array/ISO, expression indexes V20261106 đã có | Giữ nguyên; temporal regression tiếp tục chạy |
| Feed cache | Viewer-keyed, hit reload/authorize; trước đây không kiểm tra lại follow scope trên Following hit | IMPLEMENTED live follow scope; regression khi eviction không tới |
| Cache stampede | Caffeine có thể load cùng candidate nhiều lần khi miss đồng thời | IMPLEMENTED sync=true, test 8 callers/1 candidate load, 7 rechecks |
| Search frontend | Request cũ tiếp tục chạy và có thể paint trong 300ms debounce; keyword cache không giới hạn và trả lại payload chưa revalidate | IMPLEMENTED abort/token ngay khi input đổi, giới hạn cache, fresh query luôn revalidate |
| Feed media | Tất cả ảnh lazy; video preload metadata ngay trên Feed | IMPLEMENTED first-card image eager/high priority; video preload none |
| Profile/response hydration | Profile stats aggregate, Feed author/reaction/repost batching và current access batch đã có | Already Correct; không thay code để tạo lại batching |
| Comments/reactions | Bounded pages/cursor và authorization hiện hữu | Giữ nguyên, regression; chưa benchmark dataset comments lớn |
| Chat | Conversation keyset + window latest, batch unread/user/presence; history cursor và unsend authorization đã có | Giữ nguyên reliability; những repository unbounded cũ không có production consumer |
| Notifications | Bounded 50 và cursor size+1, unread count, outbox batch/retry/idempotency đã có | Giữ nguyên; không đổi delivery guarantee |
| Search backend | User trigram indexes + custom plan trong read-only transaction; post full-text JSONB index đã có | Giữ nguyên, không thêm wildcard scan hoặc index đoán mò |
| HTTP/resources/pools | Compression, HTTP/2, content-version resource chain, prod template cache, bounded Hikari/Tomcat/async đã có | Already Correct; không tự tăng connection/thread pool |

Chưa có production tracing/pg_stat_statements nên **chưa xác định endpoint chậm nhất toàn hệ thống**.
Các hotspot ở trên được xác nhận từ source và workload test, không thay thế profiling dữ liệu thật.

## 2. SQL và execution plans

SQL EXISTS mới dùng cùng compiled WHERE và bound parameters, bỏ count aggregate khi cần boolean.
Không thay count APIs phục vụ counters/pagination và không bỏ authorization predicates.

Candidate query dùng `userId = self OR userId IN (SELECT value FROM aloute_read_social_following_authors WHERE viewer_id=?)`,
giữ `createdAt valid`, moderation/repost/tag/keyset và ORDER BY temporal key DESC, document_id DESC LIMIT.
Scope không có follow đi qua `hasFollowing` của Social và query posts riêng của viewer.

`EXPLAIN (ANALYZE, BUFFERS)` được thu trên DB test, lưu trong `docs/performance/evidence/` sau final gate.
Plan cần có Index Scan `ix_doc_posts_created_v2`, hashed membership subplan và LIMIT; nhánh rỗng dùng author index.
Không cần thêm index: timestamp expressions và friend uniqueness index đã có.

Kiểm tra force_generic_plan phát hiện generic collection parameter làm mất partial index:
Parallel Seq Scan + top-N Sort; artifact cuối ghi khoảng 8870ms trên fixture; collection literal nội bộ dùng Index Scan
`ix_doc_posts_created_v2`, khoảng 34ms trong phép kiểm tra đó. Run đầu là 6349/14ms; đây là structural plan check
chạy lúc regression suite hoạt động, không phải controlled latency benchmark hoặc p95.
`PostgresDocumentStore.find` nay cố định collection từ `@PgDocument` sau kiểm tra regex `[a-z][a-z0-9_]*`;
không nội suy author/search/viewer/cursor/limit từ request. Unit test kiểm tra request value vẫn bind và annotation độc hại bị từ chối.
`prepared-plan-check.sql` có assertion về index/no-sort, chạy READ ONLY với SET LOCAL, không đổi global planner config.
Phạm vi sửa generic plan là `find`; các write/lock/count/latest-per-conversation queries chưa được tuyên bố đã tối ưu generic plan.

Thử nghiệm đầu dùng UNION trong membership subquery đã **bị loại**: p95 candidate 5.000 following khoảng 3316ms,
dù chỉ có một SQL; planner thực hiện nhiều scan/sort hơn. Chỉ giảm số query không đủ chứng minh cải thiện.
Kết quả xấu được giữ tại checkpoint `union-regression.json`, không dùng làm baseline để phóng đại mức cải thiện.

## 3. File đã sửa

Production:

- `persistence/postgres/document/PostgresDocumentStore.java`: early-exit existence, owned collection constant cho read plan.
- `persistence/postgres/document/Criteria.java`, `SqlCriteriaCompiler.java`: parameterized published-view read scope, không SQL từ client.
- `posts/infrastructure/PostFeedReader.java`: set-based candidate retrieval, bounded legacy merge, live Following refresh.
- `posts/api/FeedPostReader.java`: internal public `refreshFollowingCards`, default fail-closed cho compatibility providers.
- `social/api/FollowedAccounts.java`, `social/infrastructure/JpaFollowedAccounts.java`, `repository/IFriendRepository.java`: public scope name + bounded existence provider.
- `feed/application/FeedPageQuery.java`, `FeedQueryService.java`: synchronized local loading, current follow authorization trên hit.
- `config/CacheConfig.java`: Redis TTL jitter, Caffeine stats.
- `static/js/app.js`, `templates/feed/index.html`, `templates/layout/base.html`: search cancellation/revalidation, media policy và asset version.
- `db/migration/V20261107__social_following_read_api.sql`: Social-owned read projection.

Tests/new harness:

- `DocumentPerformanceIT`, `FollowingReadViewIT`, `PerformanceHttpIT`, `RedisFeedCacheIT`, `CacheTtlTest`, `DocumentReadPlanTest`.
- `performance-search.cjs`, `performance-page.cjs`; điều kiện chờ trong `realtime-smoke.cjs`.
- Mở rộng `FeedCacheBoundaryTest`, `FeedQueryBudgetTest`, `FullBackendContextIT`.
- Cập nhật module-boundaries và báo cáo/evidence ở docs/performance.

Đường dẫn Java tính từ `src/main/java/com/aloute` hoặc `src/test/java/com/aloute`; resource từ `src/main/resources`.
Không xóa source, sửa DTO/REST schema/event format, migration đã áp dụng, Docker, wrapper hoặc frontend framework.
Test budget cũ yêu cầu stream JPA batches được thay bằng invariant mới: một existence probe và một bounded candidate SQL,
kèm integration test xác minh kết quả/owner/cursor thực tế; không đổi test chỉ để bỏ assertion.

## 4. Index và database ownership

**Không thêm/chỉnh/xóa index.** Không sửa V20261106. V20261107 chỉ CREATE VIEW đọc từ friends;
Social sở hữu mapping, Posts chỉ dùng named read interface, không ghi bảng Social.
Không UPDATE/DELETE/backfill payload hoặc chuyển timestamp qua kiểu microsecond.
JPA/JDBC vẫn cùng transaction manager/data source; không thêm distributed transaction/read-model DB.

## 5. Cache

- Cache keys/names/serialization giữ nguyên. DTO cache hiện có đã Serializable; không xây lại serializer.
- Feed local miss dùng `sync=true`, waiter vẫn đi qua current authorization.
- Following cache hit lọc bằng quan hệ Social hiện thời trong cùng query hydrate tối đa 30 IDs.
- Redis TTL jitter chỉ rút ngắn tối đa gần 10%, không vượt freshness TTL cấu hình.
- Caffeine bật recordStats để hệ metrics hiện tại đọc được hit/miss.
- Redis vẫn SCAN-clear bounded và outage trở thành cache miss; không tự chuyển sang một local fallback stale trên từng instance.
- Không tuyên bố distributed single-flight: Redis nonlocking writer không cung cấp khóa theo key giữa các replicas.
  Jitter không thay authorization hoặc đảm bảo invalidation. Multi-instance hot-key throughput chưa kiểm chứng.

## 6. Feed pipeline

Candidate retrieval: một existence probe O(1) phía client + một bounded SQL, thay O(follow_count/500) round trips.
Post visibility/moderation/block/private/community vẫn được kiểm tra bằng current AccessBatch sau candidate retrieval.
O(1) là số round trips và bộ nhớ candidate phía Java, không phải tổng công việc database:
PostgreSQL vẫn hash danh sách follows và có thể duyệt nhiều timeline entries khi quan hệ thưa.
Ví dụ plan 20 following trên fixture bỏ qua khoảng 23940 rows để lấy 50 candidates; cần đo lại ở quy mô triệu bài.
Thứ tự chronology, repost eligibility, keyset thời gian/ID, giới hạn traversal, cursor encoding và batch hydration không đổi.
Legacy API nhận explicit following list vẫn chia IN list có giới hạn nhưng chỉ giữ top-limit sau mỗi chunk, không tích lũy mọi candidate.

Không triển khai fanout inbox: benchmark local chưa chứng minh cần thêm worker/state/rebuild/idempotency để thay truy vấn này.
Query-side SQL scope chỉ lọc tác giả; **không phải authorization** và không được dùng để bỏ policy checks.

## 7. Frontend

AbortController hủy fetch cũ; token vô hiệu ngay khi gõ, đổi tab hoặc reset. API wrapper giữ AbortError.
Debounce 300ms, result shape, escape/CSP/CSRF/JWT giữ nguyên. Cache keyword dùng object không prototype, tối đa 20 keys/tab;
chỉ giữ state để append, không trả thẳng dữ liệu cũ cho fresh query.
Ảnh nội dung đầu Feed eager/high-priority; ảnh tiếp theo và avatars vẫn lazy/async.
Video Feed preload none, controls và URL nguyên trạng. Không ép tỷ lệ ảnh hoặc tự đổi layout/CSS.
Dynamic post cards dùng cùng preload none; composer preview local vẫn giữ metadata để người dùng xem file chọn.
Version app.js trong base layout tăng để tránh stale client sau deploy.

Browser race fixture trước: 0 aborted, stale result xuất hiện trong debounce. Sau: 1 aborted, không stale paint;
cùng keyword được gọi lại backend sau khi dữ liệu thu hồi, không dùng local cached payload.
Browser test còn đi qua hơn 20 keyword rồi tìm từ khóa số `0` để bảo vệ active page state khi giới hạn cache;
kiểm tra video card động còn controls và không phát request media ban đầu.
Đây là regression về request/render behavior, không phải số đo Core Web Vitals toàn website.

## 8. Benchmark trước/sau

Môi trường: Windows + PostgreSQL 18.3/Redis 8.0.5 loopback WSL, i5-12450H/12 logical processors, RAM khoảng 16GiB;
JVM test giới hạn 384MiB, 2 processors; Hikari 4. Không giả định máy này dành riêng hoàn toàn cho benchmark.
Dataset synthetic: 40.000 posts, 8.009 users, 5.020 follows; thời gian array/ISO xen kẽ, 0/20/5.000 following.
Không dùng dữ liệu/credentials production. Mỗi scenario 5 warmup + 30 samples; load 8 workers/80 requests, bounded.

Candidate benchmark gồm SQL + JSON deserialize; authorization/hydration dùng doubles, **không phải Feed HTTP latency**.
Baseline từ code cũ; final có paired run hai thuật toán cùng JVM/pool/data, đảo thứ tự bằng seed cố định và so toàn bộ candidate IDs.
p99 của 30 mẫu là max mẫu, không đủ bằng chứng cho production tail latency.

Kết quả final ở [after.json](evidence/after.json); baseline code cũ ở [before.json](evidence/before.json):

| Candidate workload | p50 trước → sau (ms) | p95 trước → sau (ms) | p99 trước → sau (ms) | SQL/batch trước → sau |
|---|---:|---:|---:|---:|
| 5000 following, paired cùng run | 169,62 → 16,10 | 222,41 → 22,96 | 226,62 → 23,22 | 21 → 2 |
| 5000 following, hai run riêng | 262,79 → 17,84 | 497,10 → 20,75 | 659,62 → 21,32 | 21 → 2 |
| 20 following | 30,62 → 31,39 | 52,83 → 35,57 | 58,42 → 35,73 | 2 → 2 |
| 0 following | 6,40 → 3,32 | 7,32 → 4,92 | 8,08 → 5,42 | 2 → 2 |
| EXISTS, 40000 matches | 6,74 → 1,26 | 8,44 → 3,37 | 9,02 → 3,47 | 1 → 1, early exit |

Reference batch implementation trong paired run dùng cùng document store/parser mới, đảo thứ tự theo seed;
đo khác biệt batch-vs-set-based, không coi nó là một bản deploy cũ nguyên vẹn.
SQL count là bằng chứng ổn định hơn tỷ lệ speedup trên desktop đang có tiến trình khác.
For You candidate không đổi thuật toán; chênh lệch run 13,99 → 13,55ms p95 không được gán thành cải thiện từ ranking.
Bounded load candidate 8 workers/80 requests: baseline p95 1084,35ms, 10,24 requests/s;
run cuối p95 74,62ms, 139,52 requests/s, 0 failed. Đây là hai run riêng, scope candidate-only, không phải HTTP capacity.

HTTP thực/JWT/current authorization dùng harness riêng, 20 mẫu/route sau 3 warmup;
chỉ có **after-only lab measurements**, không suy diễn cải thiện trước/sau:

| Route/workload | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|
| Feed Following cold | 99,04 | 148,09 | 153,15 |
| Feed Following warm | 57,66 | 70,46 | 89,14 |
| Feed For You cold | 71,60 | 104,52 | 157,57 |
| Feed For You warm | 52,18 | 70,45 | 110,69 |
| Profile | 49,33 | 57,90 | 77,84 |
| Post detail | 40,15 | 52,43 | 56,57 |
| Search users | 68,12 | 96,39 | 112,59 |
| Notifications (empty fixture) | 18,94 | 22,40 | 22,75 |
| Conversations (empty fixture) | 27,14 | 32,17 | 32,42 |
| HTML /feed | 298,16 | 467,91 | 480,39 |

0 non-200 trong 200 measured serial HTTP requests. [Raw HTTP metrics](evidence/http-after.json).
Cold được harness evict đúng viewer/cache key trước mỗi request; `cold=1` chỉ là nhãn query test, không phải API/cache bypass mới.
Warm vẫn authorize/hydrate current cards. Dataset Notifications/Chat trống nên không dùng kết quả đó để khẳng định scale.

Một browser navigation desktop tới backend thật: LCP/FCP 2196ms, CLS 0,00096, TTFB khoảng 819ms,
55ms long tasks, 1239 DOM nodes, 441908 encoded resource bytes (không gồm navigation body/websocket frames).
Không throttle mobile, external fonts/providers bị block; không có ảnh/video thật trong fixture này.
Đây là after-only lab sample, không phải field CWV hay chứng minh INP. [Raw browser metrics](evidence/browser-page.json).

Bounded **HTTP thật** riêng (8 JWT principals, 8 workers, 80 Feed requests, sau 8 warmup):
p50 120,60ms, p95 261,00ms, p99 268,06ms, khoảng 58,64 requests/s, 0 non-200.
Current authorization vẫn chạy trên từng hit; không vô hiệu rate limit. Đây là after-only workload nhỏ,
không so throughput với candidate-only benchmark và không chứng nhận production concurrency.
[Raw HTTP load](evidence/http-load-after.json).

## 9. Tests và lệnh tái hiện

Đã chạy baseline verify có DocumentPerformanceIT và browser race fixture trước sửa.
Các targeted gates đã chạy PostgreSQL thật, temporal/null/malformed/nanosecond, Following/self/unfollow,
ordering/keyset, actual Spring wiring, current privacy/cache rechecks, Redis round-trip/outage và local concurrency.
Full gate **BUILD SUCCESS**: `mvn -B ... clean verify` — 392 unit tests (391 PASS, 1 SKIP),
58 integration tests PASS, 0 fail/0 IT skip. Test unit skip là `AlouteApplicationTests` cần DB_URL;
full production Spring composition với test PostgreSQL/Redis đã được xác minh riêng trong Failsafe.
[XML totals](evidence/test-results.json); log full tại checkpoint `clean-verify.log`.

Lượt resource verification đầu có một timeout ở multi-tab WebSocket: test chờ CONNECTED rồi gửi ngay,
trong khi app.js công bố connection trước khi broker hoàn tất SUBSCRIBE. Không sửa production WebSocket.
Test nay chờ probe fixture được nhận trên kênh thật trước sự kiện mục tiêu, đồng thời thêm assertion mỗi tab nhận đúng một lần;
giữ CSP, anonymous rejection, reconnect và cross-account isolation. Targeted verify sau sửa đã PASS.
Sau cuối cùng thay preload dynamic card/cache pruning, browser search test PASS; artifact được repackage,
resource/HTTP load verification được chạy riêng, không giả định full suite đã chạy lại trên từng thay đổi test/resource nhỏ.

| Gate | Kết quả thực tế |
|---|---|
| Full `mvn -B … clean verify` | 391 unit PASS + 1 SKIP; 58 IT PASS, 0 IT skip |
| Final targeted `mvn -o -B … -Dtest=DocumentReadPlanTest,RealtimeDependencyTest -Dit.test=PerformanceHttpIT,RealtimeSmokeIT verify` | 3 unit PASS; HTTP load + realtime browser: 2 IT PASS, 0 skip |
| `node src/test/browser/performance-search.cjs after` | PASS: abort/stale paint/revalidation/numeric query/cache limit/dynamic media |
| `psql -X -v ON_ERROR_STOP=1 … -f prepared-plan-check.sql` | PASS: READ ONLY generic plan/index assertion |

Một lượt HTTP-load targeted thiếu browser env nên RealtimeSmokeIT đã skip; đã chạy lại với env đầy đủ,
final targeted có 0 skip. Không tính lại các test trùng lặp để tăng tổng test PASS.
Logs riêng giữ tại checkpoint performance: `clean-verify.log`, `frontend-subscription-race.log`,
`frontend-final-verify.log`, `http-load-final-verify.log`, `browser-after.log`.

Biến môi trường test (chỉ loopback/disposable):

```powershell
$env:ALOUTE_SCHEMA_VALIDATION_URL='jdbc:postgresql://127.0.0.1:55432/aloute_quality_datetime_20261024'
$env:ALOUTE_SCHEMA_VALIDATION_USERNAME='aloute_test'
$env:ALOUTE_SCHEMA_VALIDATION_PASSWORD='test-only'
$env:ALOUTE_REDIS_VALIDATION_PORT='56379'
$env:ALOUTE_PERFORMANCE_DB_URL='jdbc:postgresql://127.0.0.1:55432/aloute_quality_performance_20261024'
$env:ALOUTE_PERFORMANCE_TEST='true'
$env:ALOUTE_PERFORMANCE_HTTP='true'
$env:ALOUTE_PERFORMANCE_PHASE='after'
$env:ALOUTE_BROWSER_EXECUTABLE='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$env:ALOUTE_PLAYWRIGHT_MODULE='C:/Users/buiph/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright'
mvn -B '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/20261024/performance/temp -Duser.timezone=UTC' clean verify
node src/test/browser/performance-search.cjs after
```

Đo riêng bounded HTTP load mà không lặp Search/rate-limit workload:

```powershell
$env:ALOUTE_PERFORMANCE_CONCURRENCY_ONLY='true'
mvn -B '-DtestJvmArgs=-Xmx384m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC' '-Dtest=DocumentReadPlanTest,RealtimeDependencyTest' '-Dit.test=PerformanceHttpIT,RealtimeSmokeIT' verify
```

MAVEN_OPTS cùng memory limits; TEMP/TMP trỏ thư mục temp riêng trên D:. UTC chỉ cho JVM test vì runtime PostgreSQL riêng thiếu alias Asia/Saigon.
Không đổi production timezone. HTTP performance opt-in cần dataset đã tạo bởi DocumentPerformanceIT;
không bật trên DB trống/chung hoặc kỳ vọng numbers production. Chạy candidate harness trước khi tái hiện HTTP riêng.

## 10. CPU, RAM, database và cache

Candidate harness ghi process CPU time trong workload, heap-used snapshot cuối, max heap, database bytes.
Run cuối: khoảng 8,47s process CPU, heap-used cuối 141735160 bytes (~135MiB), max heap 384MiB,
DB 86267583 bytes (~82,27MiB). Không có baseline resource cùng instrumentation để kết luận giảm tổng CPU/RAM.
Không gọi heap snapshot là peak RSS hoặc CPU PostgreSQL. HTTP harness ghi local feed cache stats.
EXPLAIN BUFFERS phản ánh đúng run/fixture; không đồng nghĩa I/O production hoặc pg_stat_statements toàn hệ thống.
Local feed cache ở serial HTTP run: 73 hits, 42 misses/loads, 0 load failures (~63,5% hit rate);
cold workload cố ý evict nên đây không phải production hit ratio.
HTTP load riêng: 80 hits, 8 initial misses/loads, 0 load failures; không tính đây là production cache ratio.
Redis memory snapshot sau test: 1207272 bytes, process peak lúc đó 1213608 bytes; không coi là peak của riêng benchmark.
Chưa có đo CPU/RSS PostgreSQL/Redis xuyên toàn bộ workload; không khẳng định giảm tổng RAM/CPU của website.

## 11. Chưa kiểm chứng

Production data, p95/p99 under sustained distributed load, LCP/INP/CLS field/mobile/throttled network,
large chat/comment histories, notification fanout, sparse follows trên hàng triệu posts, celebrity distribution,
Redis hot-key multi-instance, external storage/Firebase/RabbitMQ/TURN: **NOT VERIFIED**.
HTTP empty Notifications/Chat không chứng minh tốc độ với nhiều message/notifications.
Docker daemon/k6 không dùng; bounded Java Executor/HttpClient và Playwright là công cụ test hiện có, không coi là capacity certification.

## 12. Rollout, migration và rollback

Backup/quy trình staging hiện có; apply V20261107 trước code mới phục vụ request.
CREATE VIEW là thay đổi metadata, không build index hoặc backfill nhưng vẫn cần lock ngắn;
kiểm tra trên schema/version triển khai thật. Không tự chạy production migration.
Giữ read view khi rollback app; code cũ bỏ qua view, không cần drop hoặc đảo payload.
Không sửa Flyway checksums. Có thể rollback từng lát cắt/cache/frontend bằng artifact trước,
nhưng luôn giữ bản vá datetime V20261106. Không flush toàn Redis hay chạm outbox/call records.
API/cache serialization/cursor không đổi; frontend version cần tăng tiếp nếu rollback assets đã cache.

## 13. Còn lại và đánh giá

| Hạng mục | Trạng thái |
|---|---|
| Exists, candidate retrieval, owned-collection generic plan, current Following cache, local miss synchronization | IMPLEMENTED / TESTED; candidate + EXISTS BENCHMARKED trong lab |
| Redis jitter/stats, search cancellation/revalidation, Feed media hints | IMPLEMENTED / TESTED; chưa có production perf claim |
| REST/MVC/Chat/Call/Notification/temporal compatibility | TESTED: full gate 391 unit + 58 IT PASS, 1 unit SKIP; final resource/HTTP load/realtime targeted 3 unit + 2 IT PASS |
| Full-stack production performance targets | PARTIALLY COMPLETE / NOT VERIFIED |

Ưu tiên tiếp: staging workload có distribution thật, tracing/query counts theo HTTP request,
prepared generic-plan soak test, sparse/celebrity feeds, cold DB cache, p95 load dài, mobile CWV/INP,
large conversation window-query plans và Redis multi-instance hot-key/failure tests.
Chỉ sau số đo đó mới cân nhắc index bổ sung, read model hoặc hybrid fanout; không coi việc thêm kiến trúc là một tối ưu tự thân.

Đã code và kiểm chứng các lát cắt nêu trên; **BENCHMARKED** chỉ áp dụng đúng workload/fixture đã đo.
Toàn bộ mục tiêu production full-stack vẫn **PARTIALLY COMPLETE** vì chưa có staging distribution, field CWV/INP
và sustained multi-instance load. Source/artifact hiện tại review được; không deploy, push hoặc migrate production.
Workspace thiếu Git: cần lưu/version-control source và artifact theo pipeline trước rollout; không giả định có sẵn một historical artifact rollback trong workspace này.
