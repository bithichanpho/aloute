# Báo cáo ALOUTE Backend Architecture Refactor — 24/10/2026

> Cập nhật bugfix 24/10/2026: Calls nay dùng PostgreSQL durable state/leases, atomic history + notification outbox và callId uniqueness/reservation; CSP realtime đã self-host. Kết quả kiểm chứng mới nhất: [backend-bugfix-report.md](../quality/backend-bugfix-report.md). Nội dung dưới đây là checkpoint architecture trước đợt bugfix này; migration toàn bộ legacy vẫn PARTIAL.

## 1. Verified Architecture Snapshot

**PARTIALLY COMPLETE / PARTIAL.** Đã triển khai và kiểm thử nhiều vertical slices có consumers, Spring wiring và implementation thật; chưa đạt toàn bộ Definition of Done trong AGENTS.md. Chi tiết checkpoints: [backend-audit.md](backend-audit.md), [continuation-report.md](continuation-report.md), [cache-and-calls-report.md](cache-and-calls-report.md).

- Java target 21, JDK chạy test 22, Maven 3.9.11; Spring Boot 4.1.1, Framework 7.0.9, Security 7.1.1, Spring Data BOM 2026.0.1, Hibernate 7.4.5.Final theo build/BOM.
- Một Spring Boot deployable; PostgreSQL JPA + JDBC document JSONB dùng cùng DataSource/transaction manager; Redis; RabbitMQ STOMP relay/simple broker fallback. REST, Thymeleaf, STOMP/WebSocket, WebRTC; FastAPI moderation/Firebase/Cloudinary/mail giữ nguyên.
- Source hiện tại không có GraphQL schema/dependency/resolver hoặc MongoDB. Không thêm công nghệ dựa trên mô tả snapshot lịch sử.
- Ban đầu: 269 production Java files, 74 test classes, chủ yếu controller/service/repository. Lượt 1: 296/86; lượt 2: 320/96; hiện tại: **325/104**. Không có `.git`; theo yêu cầu user tiếp tục bằng original checkpoints/SHA manifests, không reset/git init/ghi đè baseline.
- Already Correct trước refactor: outbox UUID/dedupe/retry/backoff/dead-letter/ShedLock, batch authorization, keyset Feed, atomic message receipts/clientId dedupe/group membership, Redis call state validation, media tombstones/reference checks. Không tuyên bố snapshot có đầy đủ Chat Reply/Reaction/Edit/Pin hoặc ranking/snapshot/HMAC theo tài liệu lịch sử.

## 2. Module Migration Status

| Module | Trước refactor | Đã thay đổi | Boundary Test | Functional/Integration Test | Trạng thái |
|---|---|---|---|---|---|
| Identity | Auth/token/2FA legacy | ActionRateLimit/SessionInvalidation; notification cleanup qua owner | Public APIs + collaborator guards | Auth/refresh/cache-after-commit regression; real DB rollback chưa chạy | Implemented slices / Partially Tested |
| Users | Entity/repository xuyên services | UserDirectory, UserAccountAdministration, CallParticipants projections/providers | APIs không persistence/credentials | ADMIN/HOST/self protections; profile/directory/batching; DB chưa chạy | Implemented slices / Partially Tested |
| Social | SocialFeatureService trộn post/account/social | Edit/history/saved qua Posts; account actions qua Users/Admin/Identity | No SavedPost persistence, no private identity/User repo calls | Social/privacy/report-audit regression; social writes còn legacy | Implemented slices / Partially Tested |
| Posts | Trộn cursor/feed/edit/history/saved/write | PostEditing/SavedPosts, immutable views, owned feed reader/refresh, notification cleanup, PostChanged hint | Public collaborators + owned persistence; no migrated cycles | CAS/media/history/saved/authorization/REST; SQL integration còn thiếu | Implemented slices / Partially Tested |
| Feed | Cache và traversal trong PostService | FeedQuery + internal cached traversal, current batch authorization trên hit; cache/listener về owner | Business/infra guards; legacy facade không có cache bypass | Caffeine thật, batch privacy/order/cursor/fail-closed/REST tests | Implemented cursor slice / Partially Tested |
| Communities | Membership/roles/cleanup legacy | CommunityReviewAccess, CommunityRoles, notification cleanup owner | Public cross-module APIs | Membership/demotion/deleted group + management tests; concurrency DB chưa chạy | Implemented slices / Partially Tested |
| Messaging/Calls | ChatService/CallTimeoutService legacy | CallRegistryService trong messaging.calls; CallSignaling/MissedCallHistory; Users/Notifications APIs; legacy facade | Strict Calls dependencies và scoped cycles | Local state/race/idempotency, Spring wiring, real registry controller contracts; Redis/TURN chưa chạy | Implemented Calls slice / Partially Tested; Chat internals còn |
| Notifications | User/Post internals + reference cleanup | Owner application/APIs/outbox/late visibility; bulk JPQL giữ long adapters | Owned persistence + public ports | Worker retry/privacy/dedupe/wiring/cleanup; SQL IT skip | Implemented / Partially Tested |
| Media/Moderation | Lifecycle/retry có sẵn | Default-off physical deletion/dry-run guards, PostMediaAttachments/ModerationPolicy seams | Safety policy owner; migration chưa toàn bộ | Retention/moderation test doubles; storage/DB race chưa chạy | Implemented safety slices / Partially Tested |
| Administration | Social trực tiếp sửa User | AccountModerationService điều phối Users/Identity | Chỉ public owner APIs | Roles, transaction interceptor commit/rollback, report audit; DB/dashboard còn | Implemented slice / Partially Tested |

Kiểm tra kiến trúc chỉ áp dụng các packages/lát cắt đã migrate; không tuyên bố toàn legacy graph hết cycle hay toàn module đã DONE.

## 3. Dependency / Architecture Violations

| Source Module | Forbidden Dependency | Fix | Test Evidence |
|---|---|---|---|
| Notifications | User/Post repositories/entities/private policy | UserDirectory/ProfileVisibility/PostNotificationLookup public APIs | ModuleBoundaryTest; worker/application/privacy tests |
| Post/Community/Account cleanup | Notification repositories | NotificationCleanup/NotificationAccountCleanup owner implementations | Owner cleanup + transaction/contract tests |
| Social edit/history/saved | Owned Posts persistence/write logic | PostEditing/PostRevisionView/SavedPosts/PostCards | Posts tests; no SavedPost dependency guard |
| Social account moderation | User repo/private token/cache methods | Administration → Users/Identity public APIs | Role/security/session/transaction-interceptor tests |
| Feed | Post internals and cache bypass | FeedPostReader adapter owns SQL/visibility; FeedQuery facade outside cache | FeedCachedVisibilityTest RED→GREEN; FeedCacheBoundaryTest; FeedWiringTest |
| Calls | Concrete ChatService/User repository | MissedCallHistory/CallParticipants APIs; registry in messaging.calls | Strict Calls rule; CallRegistryWiringTest; CallParticipantsTest |
| STOMP Calls adapter | Concrete timeout service/private session | CallSignaling public session view | CallSignalingContractTest with real registry |

Owned legacy entities/repositories giữ package/table mappings để tránh thay schema cùng lúc. Compatibility constructors có imports chuyển tiếp rõ ràng; implementation mới không dùng shared/common để giấu foreign dependencies. Xem [module-boundaries.md](module-boundaries.md), [ADR](adr-0001-incremental-module-apis.md).

## 4. Changed Files

Danh sách SHA-derived đầy đủ: [changed-files.md](changed-files.md). **Cộng dồn: 56 production Java files mới, 30 test classes mới, 7 architecture documents mới và 36 file cũ sửa; 0 file bị xóa.**. Không xóa source, không migration.

- Lượt 3: FeedPageQuery/FeedQueryService, FeedPostReader/PostFeedReader, PostService, Feed wiring/visibility/boundary tests; CallSignaling/CallRegistryService/CallParticipants/JpaCallParticipants, CallTimeoutService facade, ChatController, Calls/controller/Users tests, ModuleBoundaryTest và docs.
- Public REST DTOs/URLs/methods/envelopes, STOMP mappings/destinations/payload field names, WebRTC signaling và database/document/ID formats giữ nguyên. Đối chiếu baseline đầu phiên: 203 DTO/model/resource hashes không đổi; 4 Redis Lua bodies so sánh byte-text khớp trước/sau. Cache key/value namespace/serialVersionUID giữ nguyên.
- Intentional fixes: privacy-only edit global eviction; cached cursor Feed cards recheck current authorization khi eviction không chạy; delayed notification recheck quyền; failed WebSocket timeout send không chặn history/intent; physical deletion mặc định tắt; post deletion/notification cleanup cùng PostgreSQL transaction.
- ArchUnit 1.5.1 chỉ test scope. Mockito explicit startup agent là sửa tối thiểu cho baseline test blocker; giữ late-bound argLine. Không thêm runtime dependency/infrastructure hoặc sửa deployment/wrapper.

## 5. Security and Business Rules Preserved

- Principal-derived identity, admin/host/self-account protections, notification receiver ownership và anonymous/foreign-object rejection vẫn tại backend. Users public projections không đưa password/email/token qua boundary.
- Posts giữ author/media/CAS rules, privacy-only moderation semantics, bounded history và saved-post batches. Feed dùng cùng authoritative AccessBatch cho block/private/community/repost-original; cache hit reload card/nested body hiện tại, fail closed khi DB/policy lỗi. Không dùng TTL/eviction làm quyền đọc.
- Feed thứ tự/keyset/cursor/hasMore và thuật toán traversal giữ nguyên; trang có thể sparse khi card mất quyền. Cache miss không hydrate thêm lần thứ hai. Không thêm whole-table read hoặc pagination trong RAM.
- Calls giữ caller/callee/peer checks, Redis keys/scripts/TTL và transitions; facade/port chung registry, một scheduled poll. Test contract xác minh callerUserId spoof không thay server identity, third party/blocked peer bị từ chối và double hangup không phát signal lặp.
- Notification outbox/dedupe/retry/backoff và reference type filters giữ nguyên. Provider push/WebSocket vẫn at-least-once; không tuyên bố exactly-once. Versioned PostChanged chỉ cache hint sau commit, không durable event bus.
- Chat persistence/receipts/member authorization và media reference retention giữ nguyên. Physical deletion default-off/dry-run-on; không chạy cleanup/migration phá hủy hoặc production infrastructure.

## 6. Test Results

| Command/Test Suite | Passed | Failed | Skipped | Lý do/Ghi chú |
|---|---:|---:|---:|---|
| Lượt 2 baseline sau explicit Mockito agent | 326 | 0 | 1 | 327 total, log architecture-continuation-baseline-agent.log |
| Lượt 2 full verify | 357 | 0 | 1 unit + 1 IT | architecture-continuation-verify.log; executable JAR built |
| Lượt 2 bulk contract/SQL opt-in | 2 | 0 | 1 IT | NotificationBulkCleanupIT thiếu disposable DB |
| Feed stale-visibility regression trước sửa | 0 | 1 expected | 0 | architecture-cache-read-red.log |
| Feed expanded gate | 42 | 0 | 0 | architecture-cache-read-gate.log |
| Timeout delivery regression trước sửa | 0 | 1 expected | 0 | architecture-call-delivery-red.log |
| Calls gate | 26 | 0 | 0 | architecture-calls-gate.log; gồm 12 architecture rules |
| Full verify đầu lượt 3 | 382 | 1 | 1 | Test mới thiếu candidate trong expected fields; giữ production contract, sửa expected theo payload/source |
| Full verify lượt 3 sau sửa test | **383** | **0** | **1 unit + 2 IT** | 384 unit total; 2 DB IT skipped; BUILD SUCCESS/JAR built; architecture-cache-calls-verify-rerun.log |
| Fresh-output verify cuối cùng | **383** | **0** | **1 unit + 2 IT** | architecture-cache-calls-fresh-verify.log; bytecode mới từ đầu, JAR không còn inner class Calls vị trí cũ |

Lệnh thực tế đã chạy từ workspace này (PowerShell, chỉ environment của tiến trình build):

```powershell
$env:TEMP='D:\aloute-develop123\aloute-develop123\target\test-temp'
$env:TMP=$env:TEMP
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-develop123/target/test-temp'
mvn -o -B '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-develop123/target/test-temp' verify
```

Gate chọn lọc dùng cùng env/argLine với `-Dtest=FeedCachedVisibilityTest,FeedCacheBoundaryTest,PostFeedRefreshTest,FeedWiringTest,FeedQueryServiceTest,FeedQueryBudgetTest,PostControllerWebTest,ModuleBoundaryTest test`; Calls dùng `-Dtest=CallTimeoutServiceTest,CallTimeoutDeliveryTest,CallRegistryTest,CallRegistryWiringTest,CallParticipantsTest,ChatControllerStompTest,ScheduledJobLockingTest,ModuleBoundaryTest test`.

Sau khi phát hiện bytecode inner class Calls cũ còn trong incremental JAR, đã chuyển riêng target/classes và target/test-classes sang checkpoints bên trong workspace (kiểm đường dẫn trước move), chạy lại cùng lệnh verify từ class outputs trống. Không dùng Maven clean làm mất source snapshots. Kiểm JAR cuối có FeedPageQuery/CallRegistryService mới và không có CallTimeoutService$LocalCall cũ.

Docker CLI có nhưng daemon không kết nối được. Không có disposable test PostgreSQL URL/credentials. Schema/bulk-SQL IT là opt-in và skip; full application integration, Redis/RabbitMQ nhiều instance, Firebase/Cloudinary/TURN và production-like load tests **NOT RUN**, không coi unit doubles là SQL rollback/storage integration.

## 7. Performance

**NOT VERIFIED** cho SQL plans/EXPLAIN ANALYZE/p95/throughput/concurrent-user capacity. Test doubles kiểm tra bounded batch và số lời gọi port/store; không phải đo SQL thực tế. Feed cache hit thêm current authorization + card reads để bảo vệ privacy; chi phí latency cần benchmark trên disposable environment/dataset. Không tuyên bố refactor nhanh hơn hoặc chịu 10k/100k/1M concurrent users.

## 8. Remaining Issues

- P0: không xác nhận P0 mới trong các lát cắt đã kiểm chứng; không đồng nghĩa toàn hệ thống đã pentest.
- P1: Redis timeout claim xóa state trước PostgreSQL history/outbox. Process crash/history/enqueue lỗi vẫn có thể mất side effect; cần durable recovery/lease + idempotency và real integration. WebSocket isolation chỉ giải quyết failure trong cùng lần chạy.
- P1: Post/Comment moderation/report trong SocialFeatureService, social relationship writes, identity/user writes, community-post cleanup và nhiều Chat/media/admin use cases chưa chuyển owner; không coi strict rules ở packages mới là toàn backend đã modular.
- P1: real SQL transactions/notification JPQL/outbox crash/retry, storage attach-vs-cleanup và reference graph cần disposable DB/storage. Default-off không giải quyết mọi race nếu bật physical deletion sau này.
- P1: caches/read paths ngoài cursor Feed facade vẫn cần audit; cursor slice đã có hard revalidation, không khẳng định tất cả cache hệ thống đã được xử lý.
- P2: benchmark cache-hit revalidation/bulk queries, hoàn thiện envelope/metrics/correlation khi có consumer và measured need; giảm compatibility constructors sau migration. Không thêm broker/GraphQL/read database khi thiếu nhu cầu.

## 9. Final Assessment

**PARTIALLY COMPLETE.** Implemented + Tested các slices nêu trên bằng unit/contract/security/local concurrency/actual Spring wiring/scoped architecture; integration còn thiếu môi trường và legacy ownership còn tồn tại. Không chỉ tạo packages/interfaces; không đổi frontend/framework/database, không xóa dữ liệu, không push/merge/deploy.

Tiếp theo: tiếp tục Posts/Comment moderation/report và social/identity writes theo ownership; cung cấp disposable PostgreSQL/Redis và Docker daemon để kiểm chứng SQL/timeout recovery/real infrastructure. Redis-to-PostgreSQL recovery cần thiết kế/test riêng trước khi tuyên bố durable missed-call delivery.

Rollback/review bằng patches và originals đã kiểm SHA: `target/architecture-refactor.patch` cộng dồn, `target/architecture-continuation.patch` từ đầu lượt 2, `target/architecture-cache-calls.patch` lượt 3. Không rollback tự động; lưu snapshots/patches ngoài target/version control trước `mvn clean`. Không có schema migration phải đảo.
