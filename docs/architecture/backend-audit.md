# Backend audit — 24/10/2026

## Nguồn và baseline

Đọc toàn bộ AGENTS.md, pom.xml, 269 Java production files qua inventory imports/entrypoints/persistence, 74 Java test files qua inventory và các test liên quan, migrations, cấu hình transaction/WebSocket, JavaScript chat và tài liệu vận hành. Không có README gốc. Không đọc `.env` hay `.secret`. Không có `.git` ở workspace: `git status --short` thất bại; không thể xác minh thay đổi trước đó hoặc tạo branch. Source hiện tại là baseline, không suy diễn từ ZIP/tài liệu cũ.

`mvn -B test` trước sửa: **281 tests, 0 failures, 0 errors, 1 skipped**, BUILD SUCCESS. Log: `target/architecture-baseline.log`. Inventory: `target/architecture-inventory.json`; SHA-256 ban đầu: `target/architecture-initial-hashes.json` (không chứa YAML/secrets). JDK chạy thực tế 22; Maven 3.9.11; release target Java 21.

## Stack xác minh

- Một Spring Boot 4.1.1 deployable; Spring Framework 7.0.9, Security 7.1.1, Spring Data BOM 2026.0.1, Hibernate 7.4.5.Final (BOM local).
- PostgreSQL JPA và document JSONB qua `PostgresDocumentStore`, bảng `app_document_records`. Cùng DataSource/JpaTransactionManager, không phải MongoDB; không có giao dịch phân tán hai database.
- Redis cache/presence/rate limit/call state; Caffeine fallback tùy profile. RabbitMQ làm STOMP broker relay; simple broker fallback. Không có business event broker riêng.
- REST, Thymeleaf, STOMP/WebSocket/WebRTC; không có GraphQL dependency/schema/resolver. Không bổ sung GraphQL khi chưa có nhu cầu được chứng minh.
- Flyway migrations đến V20261102; Cloudinary, Firebase, mail và FastAPI moderation adapter; Micrometer/Prometheus/tracing, request correlation và ShedLock đã có.
- Docker CLI 29.8.0, daemon không kết nối được. Không chạy compose/production migration. Không cung cấp DB test trong phiên này; integration/multi-instance/FCM/Cloudinary/TURN thật chưa xác minh.

## Ownership và dependency hotspots trước sửa

| Module/Owner | Entrypoint | Persistence | Dependencies | Risks | Action |
|---|---|---|---|---|---|
| Identity | AuthController, AccountSecurityController | refresh_tokens, pending_users, account_security | users, Firebase, social/cleanup | AccountSecurity ghi notification/push/social trực tiếp | Giữ auth; migrate dần ownership |
| Users | UserController, UserService | users, roles | social, feed, media | lookup trả entity có credential | Public directory chỉ trả username |
| Social | Friend/CloseFriend/SocialFeatureController | friends, close_friends, user_blocks, follow_requests | posts, users, identity, notifications | SocialFeatureService gồm cả post edit/report | Giữ policy; public visibility API |
| Posts | Post/CommentController | posts, comments, likes, reposts, saved_posts, post_revisions, comment_reactions | social, users, moderation, media, notifications | PostService 1125 dòng/31 imports; ghi notification repo | Post lookup API; cleanup qua owner |
| Feed | PostController, DiscoveryService | query posts + Redis feedPages | posts, social, users | feed nằm trong PostService; merge follow windows | Tách orchestration bằng public read APIs, giữ cursor/order |
| Communities | CommunityController | communities, community_members, community_cleanup_jobs | posts, notifications, media | CommunityDataCleanup ghi posts và notifications | Cắt notification persistence dependency trước |
| Messaging/Calls | Chat/Group/WebRtcController | chat_messages, chat_groups, group_members, group_read_status; Redis call | users, social, media, notifications | missed-call retry qua Redis; callback delivery | Giữ state machine; kiểm thử regression |
| Notifications | Notification/PushTokenController | notifications, notification_outbox, user_push_tokens | User/Post repos, ContentAccessService, Firebase | direct internals; worker gửi stale post notification | Pilot API + implementation + retry/privacy tests |
| Media | UploadController, lifecycle worker | post_media_assets + Cloudinary | User/Community/Post/Chat refs | physical delete chưa có default-off/dry-run guard | Khóa physical delete mặc định; giữ tombstones |
| Moderation | workers, SocialFeatureController | moderation fields trên posts/comments; content_reports | FastAPI, posts/media/feed | ownership còn pha trộn | Giữ CAS/lease/retry đã có, không viết lại workflow |
| Administration | AdminAccount/HostDashboardController | account_admin_audit, users | identity, users, communities | read/write cross-domain còn legacy | Giữ role/auth checks và audit; chưa migrate toàn bộ |

## Phân loại bằng chứng

| Loại | Bằng chứng | Xử lý |
|---|---|---|
| Already Correct | Durable notification_outbox UUID, unique notifications.outbox_event_id, backoff/dead letter, ShedLock; existing delivery tests | Giữ một outbox, không hứa exactly-once |
| Already Correct | ContentAccessService có batch posts/originals/users/friends/communities và negative tests; feed cursor/keyset | Giữ policy/order/JSON/cursor/cache keys |
| Already Correct | Chat clientId dedupe, atomic receipts, group membership, call Redis transitions; media tombstone/ref checks | Giữ và chạy regression |
| Architecture Violation | NotificationService import IUserRepository/IPostRepository, external PostService/CommunityDataCleanup/AccountSecurity ghi notification repo | Public owner APIs, compatibility adapters có giới hạn |
| Architecture Violation | controller/service/repository global; chưa có Modulith/ArchUnit; SocialFeatureService trộn social/post | Architecture tests cho từng boundary đã chuyển; không suppress toàn hệ thống |
| Confirmed Bug | Notification worker chỉ recheck profile/block, không kiểm tra reference post đã mất quyền trước FCM/STOMP | Recheck post visibility cho loại post công khai trước delivery |
| Confirmed Bug | ImageStorageService.deleteMedia gọi destroy trực tiếp, không có physical-deletion-disabled mặc định | Default-off guard và dry-run kiểm thử; không bật xóa thật |
| Performance Risk | worker đọc bounded 10 events nhưng lookup từng user/event; feed follow windows query mỗi chunk | Batch directory API; không tuyên bố cải thiện p95 chưa đo |
| Requires Integration Testing | JPA+JDBC cùng PostgreSQL; worker crash/concurrent replica; FCM ACK rồi crash trước delete | Disposable PostgreSQL và service test credentials cần bổ sung |
| Requires Integration Testing | Chat snapshot không có reply/reaction/edit/pin models/endpoints; delete-for-everyone hiện xóa message | Không khẳng định có Chat V2 đầy đủ; không tự thêm trong package refactor |

## Trình tự

1. Architecture guard + notifications pilot, owner APIs Users/Social/Posts, compatibility facade.
2. Notification cleanup trả về owner; kiểm thử retry, quyền và contracts.
3. Feed/public read boundary nếu tách được mà không thay ranking/cursor/cache.
4. Messaging/calls/media/admin review, cải tiến độc lập an toàn; giữ danh sách chưa migrate.
5. Full unit/contract/architecture suite, Failsafe, tài liệu rollback và báo cáo PARTIAL khi chưa đạt toàn bộ DoD.

Không thay schema, không đọc DB thực, không benchmark trên production. Throughput/p95/EXPLAIN ANALYZE: **NOT VERIFIED**.


## Cập nhật lượt 2 — 24/10/2026

- Confirmed Bug: privacy-only PUBLIC → ONLY_ME edit giữ cache của viewer khác. Regression dùng cache thật đã FAIL trước sửa/PASS sau sửa; Posts phát cache hint cho Feed để global evict. Chưa chứng minh tránh stale private cache khi crash/Redis invalidation thất bại.
- Architecture Violation đã xử lý theo slice: SocialFeatureService edit/history/Saved Posts về Posts; User state/role/review về Users; session revocation qua Identity; Administration điều phối account moderation. Social facade không gọi User repository/private identity methods.
- Performance Risk reference batch/type deletes: đổi derived entity deletes thành bulk JPQL commands, giữ long-returning adapters. Có Spring Data default-dispatch contract test; real SQL integration opt-in mới đã compile và skip vì thiếu DB.
- Source hiện có 320 production Java files, 96 test classes. Full verify mới: 357 pass, 0 failures/errors, 1 context skip; schema IT skip. Targeted bulk verify: 2 pass, SQL IT skip.
- Môi trường: Mockito dynamic attach thất bại; pom test JVM nạp agent rõ ràng. JVM thiếu native memory và C hết chỗ; dùng memory caps/TEMP trên D, không xóa dữ liệu/đóng tiến trình người dùng. Thiếu Git metadata không là blocker theo yêu cầu.
- Chi tiết: [continuation-report.md](continuation-report.md). Stack, persistence, migrations và frontend giữ nguyên.


## Cập nhật lượt 3 — sự thật đã kiểm chứng

- Confirmed Bug: cached Feed card vẫn lộ sau ONLY_ME nếu eviction không chạy; RED test xác nhận, đã sửa bằng current batch authorization + fresh card mapping trên cache hit. Không thay cursor/key/cache payload, không hydrate lặp trên miss.
- Confirmed Bug: timeout WebSocket exception ngăn history/notification; RED test xác nhận, đã cô lập từng send. Calls state machine chuyển sang messaging.calls, public CallSignaling và Users CallParticipants có provider/wiring thật. Redis claim crash gap chưa sửa.
- 325 production Java, 104 test classes; 203 DTO/model/resource baseline hashes không đổi, 4 Lua script bodies không đổi. Feed gate 42 pass, Calls gate 26 pass. Full verify xem báo cáo mới nhất.
- Docker daemon vẫn không hoạt động khi kiểm tra lại. Không dùng service/database production. Chi tiết: [cache-and-calls-report.md](cache-and-calls-report.md).

Full fresh-output verify lượt 3: 383 unit pass, 0 fail/error, 1 context skip; 2 SQL/schema IT skip. JAR kiểm tra không còn moved legacy inner class; log architecture-cache-calls-fresh-verify.log.


## Checkpoint bugfix tiếp theo — 24/10/2026

Calls Redis claim gap và history idempotency đã được xử lý bằng durable PostgreSQL session/lease + atomic history/outbox/completion, có real DB/Redis và independent JVM fault/recovery tests. 3 migrations expand-only/unique-guard/historical-ID reservation, không sửa payload cũ. CSP dùng local pinned bundles. Nginx/wrapper đã có đúng trong source và được kiểm chứng, không sửa theo giả định báo cáo cũ. Xem [báo cáo bugfix](../quality/backend-bugfix-report.md); 386 unit + 22 IT pass, 1 original context skip. Production topology/rollout chưa READY.
