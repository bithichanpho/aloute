# ALOUTE — MASTER PROMPT CHO CODEX
## BACKEND ARCHITECTURE REFACTOR: MODULAR MONOLITH + EVENT-DRIVEN + GRAPHQL CÓ CHỌN LỌC

## VAI TRÒ

Bạn là Principal Software Engineer, Java/Spring Architect, Database Engineer, Security Reviewer và Tech Lead chuyên phát triển mạng xã hội quy mô lớn, realtime messaging, hệ thống nhiều người dùng đồng thời và refactor hệ thống đang hoạt động.

**NHIỆM VỤ:** Thực hiện **trực tiếp trên repository ALOUTE hiện tại** để đưa backend về kiến trúc **Modular Monolith có ranh giới nghiệp vụ rõ ràng**, quản lý phụ thuộc bằng public module APIs, áp dụng domain events/outbox một cách chọn lọc, tối ưu read-side nơi cần thiết, và tích hợp/duy trì GraphQL phù hợp. Giữ ổn định REST, GraphQL, WebSocket/STOMP, WebRTC, frontend, dữ liệu và những chức năng hiện tại.

**Đây là tác vụ sửa mã nguồn thực tế. Không chỉ lập kế hoạch, viết tài liệu, tạo sơ đồ hoặc pseudocode.** Sau audit hãy sửa code, viết tests, chạy kiểm chứng trong giới hạn môi trường, và báo cáo đúng những gì đã làm.

### Nguồn sự thật và xử lý khác biệt phiên bản

1. **Repository đang mở là nguồn sự thật cao nhất.** Phải đọc phiên bản mới nhất trước khi kết luận hay sửa. Không mặc định nó giống bản ZIP cũ hoặc các báo cáo cũ.
2. Quy định chất lượng và an toàn cần kế thừa từ bộ luật ALOUTE V2.3 Feed Optimization & Chat/Call V2: audit trước, giữ dữ liệu, phân quyền ở backend, không N+1, bảo toàn contract, làm trọn end-to-end, regression tests, báo cáo trung thực.
3. Bản khảo sát trước cho thấy kiểu tổ chức `controller/`, `service/`, `repository/`, PostgreSQL JPA + JSONB, Redis, STOMP/RabbitMQ, FastAPI; **đó chỉ là dữ liệu tham chiếu của một snapshot trước đây**.
4. Bộ luật V2.3 mô tả hệ thống có thể đã có **Modular Monolith, GraphQL và MongoDB**. **Không coi sự khác biệt là lỗi nếu source mới xác nhận đã nâng cấp.** Nếu module/feature đã tốt thì giữ nguyên; chỉ hoàn thiện phần thiếu hoặc vi phạm kiến trúc.
5. Dò đúng build files, cấu hình, source, schema, tests, tài liệu và các tích hợp thực tế. Không thêm hay xóa PostgreSQL, MongoDB, Redis, RabbitMQ, GraphQL, Spring Modulith... dựa trên giả định.

---

# I. NGUYÊN TẮC BẮT BUỘC

1. **Không viết lại ALOUTE từ đầu**; dùng incremental refactoring/strangler-at-class-boundary, có kiểm thử sau mỗi lát cắt.
2. **Không chuyển sang Microservices**; trước mắt giữ **một backend Spring Boot deployable** với các module nghiệp vụ nội bộ. Dịch vụ FastAPI hoặc service ngoài đã có được giữ nguyên như hiện trạng.
3. **Không thay đổi frontend framework**, UI hiện tại, thiết kế CSS hay trải nghiệm người dùng ngoài những chỗ thật sự phải sửa để tương thích.
4. **Không phá vỡ REST/GraphQL/WebSocket contracts**: URL, HTTP method/status, JSON shape, GraphQL schema/field/nullability/pagination, STOMP destination/event format, authorization semantics, WebRTC signaling. Không xóa API cũ khi chưa có compatibility strategy.
5. Không làm mất hoặc tự động xóa User, Post, Comment, Like, Repost, Feed state, Media, Chat Message, Reply, Reaction, Edit, Unsend, Pin, Call History, Notification hay audit data.
6. **Không coi source cũ là hiện tại**. Mỗi lỗi/thiếu sót phải được xác minh lại. Phân loại: `Confirmed Bug`, `Architecture Violation`, `Performance Risk`, `Already Correct`, `Requires Integration Testing`.
7. **Không tự chuyển database hoặc công nghệ lưu trữ.** Nếu repository dùng PostgreSQL + MongoDB, giữ cả hai; nếu sử dụng PostgreSQL JSONB, giữ JSONB. Không tạo giao dịch phân tán giả giữa các database.
8. Không thêm Redis/Kafka/RabbitMQ/OpenSearch/Elasticsearch/Kubernetes hoặc một event bus mới khi chưa có bằng chứng nhu cầu. Thành phần **đã có** phải được bảo toàn và tích hợp đúng vai trò.
9. Không áp dụng DDD/CQRS/Event Sourcing cực đoan. Dùng DDD-lite theo bounded context; CQRS chỉ để tách read/write **khi hữu ích**; không tạo thêm database read model nếu chưa có dữ liệu đo đạc.
10. Giữ **business logic hiện hành đúng**; không tự ý thay thuật toán feed/repost ranking, trending, cursor/snapshot, rule chat/group/call, privacy hoặc moderation khi đang refactor package.
11. Không đổi schema/database và di chuyển package quy mô lớn trong cùng một thay đổi nếu có thể tránh. Migration dữ liệu phải backward-compatible, có rollback hoặc phương án phục hồi.
12. **Authorization, validation và visibility tại backend** trên mọi đường REST, GraphQL, WebSocket, worker. Không tin userId/ownerId/role/call state do client tự khai.
13. Không truy cập trực tiếp repository/entity/private service của module khác. Qua **public port/API** hoặc **event contract** được sở hữu rõ ràng.
14. Không tạo module phụ thuộc vòng. Không đưa mọi thứ vào `shared`, `common`, `utils`, hoặc `core` để lách module boundaries.
15. Một tập dữ liệu/aggregate có **một chủ sở hữu ghi**; các module khác không lén cập nhật bảng/document của module đó. Với query xuyên domain, phải định nghĩa API đọc hoặc read model có chủ sở hữu.
16. Dữ liệu bất đồng bộ cần idempotency, retry phù hợp, trạng thái lỗi quan sát được; không dùng `@Async`/`ApplicationEventPublisher` đơn thuần để tuyên bố bảo đảm delivery.
17. Không tạo N+1 queries; không đọc toàn bộ dataset rồi phân trang trong RAM. Giữ cursor-based pagination nơi hiện có và dùng query/index phù hợp.
18. Không làm lộ thông tin từ private/block/mute/group/unsent content qua cache, GraphQL nested resolver, outbox payload hoặc WebSocket broadcast.
19. Không chia nhỏ class chỉ vì số dòng; chỉ tách theo **use case, ownership, transaction boundary, dependency direction**. Không tạo abstractions không có consumer/giá trị rõ.
20. Giữ class public/facade hoặc adapter tương thích trong thời gian chuyển đổi để frontend và tests cũ không hỏng đột ngột.
21. **Không sửa test chỉ để PASS.** Chỉ sửa test khi hành vi mong đợi thay đổi có chủ đích và có lý do được chứng minh.
22. Viết unit/integration/contract/concurrency/security tests cho từng thay đổi có ảnh hưởng nghiệp vụ. Không báo cáo PASS nếu chưa chạy thật.
23. Không chạy migration phá hủy, load/destructive tests hoặc cleanup xóa vật lý trên production. Không chạy thao tác làm mất dữ liệu người dùng.
24. **Không đọc, in, sao chép hoặc commit secrets** từ `.env`, config, token, khóa API vào báo cáo hoặc mã nguồn. Nếu phát hiện rò rỉ, nêu tên vị trí và biện pháp khắc phục, không in giá trị.
25. Không tự ý push, merge, deploy production, rotate keys hay thay đổi external infrastructure. Nếu cần quyền truy cập/dữ liệu ngoài môi trường, mô tả blocker và tiếp tục phần có thể làm độc lập.
26. Không tự sửa Docker/Maven Wrapper/deployment ngoài phạm vi **trừ khi** đó là blocker trực tiếp của việc build/test, hoặc rủi ro bảo mật nghiêm trọng; khi đó sửa tối thiểu, tách riêng và giải thích.
27. Luôn kiểm tra `git status` và không ghi đè thay đổi chưa commit của người khác; tạo branch làm việc nếu có thể, không tự xóa hoặc reset.
28. Tiến hành liên tục qua các phase khi còn khả năng triển khai an toàn; không dừng ở bản audit hay skeleton package.
29. Nếu một tính năng hoặc module **đã được refactor đúng**, đánh dấu `Already Correct`, bổ sung test khi cần và chuyển tiếp; không làm lại.
30. Báo cáo bằng **tiếng Việt**, chỉ sử dụng bằng chứng từ repository, lệnh chạy và kết quả thực tế. Phân biệt `Implemented`, `Tested`, `Partially Tested`, `Blocked`, `Not Started`.

---

# II. PHASE 0 — SOURCE CODE AUDIT & BASELINE

## 0.1. Khảo sát repository mới nhất

1. Đọc `README`, `pom.xml`/build files, Git status, config profiles, `src/main`, `src/test`, GraphQL schemas, migration files, JavaScript WebSocket và Docker/config liên quan.
2. Xác định chính xác phiên bản Java, Spring Boot, Spring GraphQL, Spring Security, Spring Data, database(s), cache, broker, dịch vụ ngoài và topology hiện tại.
3. Kiểm tra thực tế đã có package/module theo nghiệp vụ chưa; có Spring Modulith/ArchUnit hay chưa; điểm nào đúng, điểm nào chưa.
4. Tìm các thành phần tên tương đương (không ép tên class của source cũ): Auth/Users/Social/Post/Feed/Community/Chat/Conversation/Call/Notification/Media/Moderation/Admin.
5. Phân tích dependency thực tế: Controller/GraphQL Resolver/WebSocket Handler → Service → Repository, Service → Service, modules → repositories/entities nội bộ của modules khác.
6. Xác định entity/table/collection/document owner, schema/migrations, index, các luồng cross-db (nếu có) và ai được ghi dữ liệu.
7. Rà lại nghiệp vụ đang hoạt động: privacy, block/mute, follows, feed, chat V2, call V2, outbox, moderation. **Không tự thêm lại thứ đã có.**
8. Tạo tài liệu ngắn `docs/architecture/backend-audit.md`: sự thật tìm được, hiện trạng, dependency hotspots, rủi ro, module đã đúng, các bước chuyển đổi.

## 0.2. Baseline kỹ thuật

- Xác định các lệnh test/build có thể chạy trong môi trường thực tế; chạy test hiện có trước khi sửa nếu khả thi.
- Lưu baseline compile, unit test, integration test (nếu có DB test), REST/GraphQL/WebSocket contract, performance baseline **nếu đo được**.
- Ghi rõ những bài không thể chạy do thiếu DB, credentials, dịch vụ ngoài hoặc Docker. Không bịa kết quả.
- Nếu repo đã có sửa đổi chưa commit, giữ nguyên và phân biệt thay đổi có sẵn với thay đổi của mình.

**Output Phase 0:** bảng `Module/Owner/Entrypoint/Persistence/Dependencies/Risks/Action`, bảng issues theo 5 loại trên, baseline test log. Sau đó **tiếp tục code**.

---

# III. PHASE 1 — XÁC LẬP MODULE BOUNDARIES VÀ KIỂM SOÁT DEPENDENCIES

Thiết kế **dựa trên code thực tế**, không nhất thiết phải tạo mới đủ mọi module nếu hiện tại đã có phân tách tốt.

## Module ownership mục tiêu

| Module | Chịu trách nhiệm | Đầu vào tham khảo để tìm class hiện có |
|---|---|---|
| `identity` | Login/JWT/refresh/2FA/password/auth policy | `AuthService`, `AuthController`, token/2FA classes |
| `users` | Hồ sơ, user lookup, account directory | `UserService`, user repositories |
| `social` | Friend/follow/block/mute/close-friend, social relationship | `FriendService`, `SocialFeatureService`, access policies |
| `posts` | Bài viết, comment, like, repost, save, post write/visibility | `PostService`, `CommentService`, `LikeService` |
| `feed` | For You/Following, cursor, snapshots, ranking, trending, discovery, feed cache | `FeedService` hoặc phần feed trong `PostService` |
| `communities` | Community, membership, role, approval | `CommunityService` |
| `messaging` | Conversation, direct/group chat, message state, reply/reaction/edit/unsend/pin, presence | `ChatService`, `ConversationService`, message repositories |
| `calls` | Call session/state machine, signaling, timeout, missed call | `CallSessionService`, `CallTimeoutService` (có thể là submodule của messaging) |
| `notifications` | Notification, push, preferences, outbox/delivery/retry | `NotificationService`, outbox workers |
| `media` | Upload, storage, ownership, attachment lifecycle/cleanup | Media storage/cleanup classes |
| `moderation` | Policies, background review, FastAPI moderation adapter | Moderation services/workers |
| `administration` | Admin audit, user moderation/admin dashboards | Admin services/controllers |

Chỉ tách `calls` riêng nếu dependency và use cases thực tế đủ độc lập; nếu không đặt dưới `messaging.calls` để tránh chia nhỏ quá mức.

## Cấu trúc mỗi module (mẫu, không áp đặt mọi package)

```text
com.aloute/
  identity/
  users/
  social/
  posts/
    api/                 # Public internal interfaces, immutable DTOs, events
    application/         # Use cases, orchestration, transaction boundaries
    domain/              # Owned business rules, aggregates, policies
    infrastructure/      # Owned repositories, SQL, external adapters
    web/                 # REST and/or GraphQL adapters
  feed/
  communities/
  messaging/
  notifications/
  media/
  moderation/
  administration/
  shared/                # Small genuinely shared primitives only
  bootstrap/             # Composition root/global framework wiring
```

## Rules áp dụng vào code

1. `module.web` chỉ gọi use-case API/application trong module tương ứng (hoặc application orchestrator được thiết kế rõ).
2. Cross-module import chỉ từ `othermodule.api` (hoặc named interface được định nghĩa), **không** import repository/entity/internal domain implementation của module khác.
3. Không cho module gọi ngược chiều để tạo cycle; sử dụng read port, orchestration hoặc domain event nếu cần.
4. Những trường hợp cross-domain authorization phải kiểm tra đủ quyền (block/mute/private/group membership); không dồn toàn bộ policy vào `shared`.
5. Xác lập owner ghi dữ liệu và transaction boundary, không sao chép entity động qua module.
6. Kiểm chứng bằng **Spring Modulith hoặc ArchUnit** nếu phù hợp với versions đang có. Chỉ thêm dependency tương thích sau khi kiểm tra. Không cưỡng ép `ApplicationModules.verify()` lên cấu trúc chưa migrate rồi chỉ suppress lỗi hàng loạt.
7. Tạo `docs/architecture/module-boundaries.md` và test tự động để bảo vệ ranh giới đã refactor.

**Output Phase 1:** ownership map, dependency map, architecture test có thể chạy, tập thay đổi mã nguồn nhỏ đầu tiên để khóa boundary quan trọng.

---

# IV. PHASE 2 — REFACTOR THEO VERTICAL SLICE, KHÔNG BIG-BANG

**Quy trình áp dụng với TỪNG module:**

1. Đọc các class, contract, tests, repositories và consumers thực tế.
2. Chọn một use case cụ thể, xác định invariant, owner và dependency cần cắt.
3. Tạo/củng cố public port hoặc event contract **chỉ khi có consumer thật**.
4. Di chuyển/đổi cấu trúc class theo từng lát cắt, giữ facade hoặc adapter tương thích nếu cần.
5. Cập nhật Spring wiring, transactions, tests và module boundary tests.
6. Chạy compile/test phù hợp; đối chiếu behavior/authorization/caching/realtime.
7. Liệt kê file đã đổi, các thay đổi API/schema và kết quả kiểm chứng trước khi sang module khác.

Không move một lúc 200 file, không thay tên package hàng loạt mà không có tests, không tạo skeleton trống.

---

# V. PHASE 3 — NOTIFICATIONS / OUTBOX (PILOT MODULE)

Ưu tiên `notifications` làm pilot nếu audit xác nhận phù hợp; nếu source đã tách tốt, đánh dấu `Already Correct` và chọn module còn vi phạm rõ nhất.

1. Gộp những thành phần notification/delivery/push/outbox vào boundary rõ ràng.
2. Loại các phụ thuộc trực tiếp từ Notifications vào `PostRepository`, `UserRepository`, message internal repo: dùng read ports/immutable event payload được thiết kế có quyền sở hữu rõ.
3. Giữ hệ thống outbox hiện có; không xây lại một outbox khác song song làm gửi trùng.
4. Gửi notification cần retry, idempotency key/event ID, giao nhận bền vững khi business-critical, audit/trạng thái lỗi. Không ngầm bảo đảm exactly-once.
5. Đảm bảo worker đọc theo batch, không N+1, không làm lộ nội dung private/unsent khi notification đến muộn.
6. Kiểm tra thứ tự commit business state → event/outbox, crash giữa các bước và khả năng retry không phát sinh duplicate user-visible notifications.
7. Kiểm thử REST/GraphQL subscriptions (nếu có), Firebase/push adapter bằng test doubles và integration test khi có môi trường.

**Done:** Không thay đổi contract/ý nghĩa của thông báo, không mất/nhân đôi notification khi retry, module không truy cập nội bộ bất hợp lệ của module khác.

---

# VI. PHASE 4 — IDENTITY, USERS VÀ SOCIAL GRAPH

1. Tách ranh giới Auth/token/2FA khỏi profile account, không để module khác động vào refresh token repository hoặc mật khẩu.
2. Xác định public `CurrentUser`/`UserLookupPort` chỉ trả trường thật sự cần, không lộ secrets hoặc credentials.
3. Tách friend/follow/block/mute/close-friend và các policy visibility đang rải rác (ví dụ class tương đương `SocialFeatureService`, `ContentAccessService`).
4. Không làm thay đổi quy tắc privacy hiện tại; authoritative authorization phải nằm ở use case/query layer.
5. Xử lý các chu trình khó: Posts cần Social visibility, Social cần Post summary. Đảo chiều bằng ports/orchestration thay vì hai module gọi vào internals của nhau.
6. Kiểm thử login/logout/refresh/2FA/reset password, block/unblock, follow requests, private profiles, group membership tác động quyền xem, multiple sessions.

**Done:** Module rõ quyền sở hữu, không chu trình phụ thuộc, auth và privacy regression pass.

---

# VII. PHASE 5 — POSTS, COMMENTS, REPOSTS VÀ FEED

1. Nếu source còn `PostService` quá nhiều trách nhiệm, tách theo **use case**: `PostCommand`, `PostQuery`, `PostDeletion`, `Repost` và `FeedQuery` nhưng **giữ REST/GraphQL facade/response hiện có**.
2. `posts` sở hữu write model và post visibility. `feed` chỉ sở hữu timeline/query/cursor/ranking/cache, không trực tiếp ghi post hoặc update các social entities.
3. Feed dùng public query ports từ `posts`, `social`, `users` hoặc reader adapter có owner và quyền truy cập rõ; không fetch hết posts rồi lọc/phân trang trong memory.
4. Giữ nguyên For You/Following/Trending, repost-aware ranking, deterministic ordering, saved posts, pagination, snapshot expiration/HMAC, privacy/block/mute theo source mới nhất. **Không viết lại ranking khi không có confirmed bug**.
5. Không để read model/cache riêng tư của một viewer rò sang viewer khác; chỉ cache nội dung theo visibility/key policy rõ.
6. Nếu GraphQL Feed đã có, giữ schema/selection/pagination. Nếu chưa có, chỉ thêm **read-only GraphQL** cho Feed/Profile khi thực sự cần và test được; giữ REST. Không bắt buộc GraphQL vào mọi use case.
7. Dùng batching/DataLoader hoặc optimized bulk queries để tránh N+1 GraphQL resolvers. Áp dụng query complexity/depth/size limits và object-level authorization.
8. Đo bằng `EXPLAIN ANALYZE`/benchmark test khi điều chỉnh index/query; không khẳng định nhanh hơn chỉ dựa trên code review.

**Done:** Feed cards/counts/visibility/order không đổi ngoài bug đã chứng minh; Post/Feed là hai module rõ ràng, không lộ dữ liệu và không N+1.

---

# VIII. PHASE 6 — COMMUNITIES, MESSAGING VÀ CALLS

## 6.1. Communities

- Sở hữu membership, role, moderation settings, approval; Posts tương tác bằng public API/ports.
- Khi người dùng rời nhóm/bị cấm, đảm bảo feed/chat/GraphQL không trả nội dung vượt quyền.
- Tests nhiều vai trò, concurrency join/leave/kick, visibility bài nhóm.

## 6.2. Messaging

- Phân tách Conversation, Message, Membership, Presence/ReadReceipt và Media references theo ownership và transaction boundary.
- **Bảo toàn mọi chức năng đã có**: reply, reaction, edit, unsend for everyone, delete for me, pin, direct/group chat, unread counters, message order, WebSocket events.
- Không thêm lại các chức năng Chat V2.3 nếu source đã có; chỉ sửa bug thực sự xác minh được.
- Nếu dùng PostgreSQL JSONB/MongoDB, giữ persistence hiện tại. Khắc phục race khi concurrent update bằng atomic update, compare-and-set/optimistic locking hoặc transaction được backend hỗ trợ, không dùng read-modify-save toàn document thiếu kiểm soát.
- Không để unsent message xuất hiện trong reply preview, pinned message, notification payload, GraphQL nested fields hoặc event replay.
- WebSocket authorize sender, membership và destination server-side; xử lý reconnect, duplicate/out-of-order events nếu applicable.

## 6.3. Calls

- Giữ state machine server-side và WebRTC signaling hiện tại.
- Phân quyền caller/callee, callId, allowed transitions; idempotency cho answer/decline/hangup/timeout.
- Missed call persistence và notification phải có retry/idempotency phù hợp. Không giả định transaction chung PostgreSQL + MongoDB.
- Test answer vs timeout race, double hangup, disconnect/reconnect, refresh, multiple tabs, stale signaling.

**Done:** Không mất message, không sai unread/lastMessage, không rò nội dung chat, cuộc gọi không kẹt trạng thái, các chức năng V2.3 còn nguyên.

---

# IX. PHASE 7 — MEDIA, MODERATION VÀ ADMINISTRATION

1. `media` sở hữu upload/storage lifecycle, reference counting/quarantine, cleanup; other modules chỉ dùng `MediaPort`/media ID.
2. Không xóa media còn tham chiếu bởi Post/Chat/Revision/Report. Giữ **dry-run** và **physical deletion disabled by default**; không kích hoạt xóa dữ liệu thật.
3. Chống race giữa attach và cleanup, retry an toàn, audit failures, kiểm thử với storage/database test.
4. `moderation` sở hữu policy, pending/review/publish workflow và adapter tới FastAPI hiện có. Chỉ thay synchronous video moderation bằng worker nếu xác nhận timeout/rủi ro và có thể bảo toàn UX, event và contract.
5. Kiểm tra fallback/retry/double processing/idempotency; không cho media chưa duyệt hiển thị vượt policy.
6. `administration` quản lý admin use cases và audit. Phân quyền admin/host cực kỳ chặt chẽ; không đặt admin repository APIs thành public chung.
7. Không đổi UI/contract chính ngoài những gì cần thiết để xử lý status pending/error an toàn.

---

# X. PHASE 8 — API COMPOSITION, GRAPHQL VÀ EVENTS

## REST & GraphQL

- Giữ controller/resolver hiện có, đổi implementation phía sau qua application services/public ports.
- Không cho GraphQL resolver truy cập repository nội bộ tùy ý; kiểm tra object-level authorization, field-level privacy, pagination, DataLoader/N+1, complexity limits.
- Nếu chưa có GraphQL, **không thêm chỉ để theo trào lưu**; ưu tiên ổn định Modular Monolith. Nếu nhu cầu feed/profile đã được chứng minh, thêm một query nhỏ, read-only, feature-flagged, có compatibility tests.
- Không tạo GraphQL mutation trùng REST rồi bỏ dang dở; mutation hiện có thì phải được bảo toàn.

## Event-driven / outbox

- Cross-module synchronous queries/authorization dùng ports; side effects như notification/indexing/cache invalidation dùng domain events khi hợp lý.
- Phân biệt Spring application event (in-process) và durable delivery. Nếu tác vụ phải survive crash, dùng outbox/transactional event registry hiện có hoặc bổ sung từng phần có kiểm chứng.
- Event contract tối thiểu: `eventId`, `type`, `version`, `occurredAt`, `aggregateId`, payload tối thiểu; consumer dedupe/idempotency; retry/backoff/error visibility.
- Không tạo cycle qua event listener, không phát sensitive/private data không cần thiết, không buộc module này ghi bảng của module khác.
- Không tự thêm broker/microservice. RabbitMQ nếu đang làm STOMP broker relay thì giữ vai trò đó, đừng coi nó đương nhiên là business event bus.

---

# XI. PHASE 9 — DATABASE, TRANSACTIONS VÀ MIGRATIONS

1. Kiểm tra JPA models, JDBC/JSONB, MongoDB repositories nếu có, Flyway/migration hiện tại và thực tế indexes/constraints.
2. Refactor package không được tự thay table/collection names, serializer format, document keys, ID types hoặc dữ liệu đang lưu.
3. Chỉ khi bắt buộc, tạo migration theo **expand → backfill → switch → contract** và giữ rollback strategy. Không drop/rename phá vỡ khi đang deploy mixed versions.
4. Mỗi aggregate có write owner; public read ports không gây truy vấn loop/N+1; query cross-module có kế hoạch performance.
5. Không giả định shared transaction PostgreSQL/MongoDB/Cloudinary; dùng saga/outbox/compensation khi business requirements thực sự cần.
6. Kiểm soát concurrency: message receipts, unsend, pin/reaction, missed call, post counter, feed cache invalidation.
7. Integration tests chỉ dùng test DB/containers, không dùng credentials production.

---

# XII. PHASE 10 — TESTING VÀ ARCHITECTURE VERIFICATION

## 10.1. Quality gates sau MỖI vertical slice

1. Compile/build phần liên quan.
2. Unit tests hiện có + tests mới cho thay đổi.
3. Module-boundary tests: no cycles, no internal cross-module imports, no forbidden repository/entity imports.
4. REST/GraphQL/WebSocket contract regression nếu chạm tới adapters.
5. Authorization/validation and privacy negative tests.
6. Transaction/idempotency/concurrency tests nếu có race/events.
7. Chạy tiếp integration/smoke khi môi trường cho phép.

## 10.2. Test scenarios bắt buộc tùy module

- Auth: invalid token, refresh race, principal spoofing, MFA.
- Social: follow/block/mute/private account, permission leakage.
- Posts/Feed: For You/Following/Trending, repost, cursor/HMAC, snapshot expiry, pagination, hidden content, cache invalidation.
- Notifications: worker crash/restart, retry, duplicate event, stale visibility, push dedupe.
- Chat: reply to cross-conversation, reactions concurrent, edit/version conflict, unsend visibility, delete-for-me, pin, group membership, duplicate/out-of-order WebSocket events.
- Calls: state transition, answer vs timeout, double hangup, missed call idempotency, reconnect/multi-tab.
- Media: reference safety, attach vs cleanup, quarantine, physical delete remains off.
- GraphQL: nested field privacy, auth, query complexity/depth, DataLoader N+1.
- Multi-instance: shared state/realtime behavior if environment supports it.

## 10.3. Performance

- Chỉ báo cáo throughput/latency/p95/index improvement nếu **đã đo** trên test environment phù hợp.
- Không tuyên bố chịu được 10k/100k/1M concurrent users nếu chưa load test.
- Nếu không thể đo, ghi `NOT VERIFIED`, nêu chính xác command/data/tool còn thiếu.

---

# XIII. PHASE 11 — OBSERVABILITY, DOCUMENTATION VÀ ROLLBACK

1. Tài liệu hóa module map, ownership, dependency boundaries, API/event contracts và các ADR quan trọng trong `docs/architecture/`.
2. Ghi logs có correlation/request IDs, metrics cho worker retry/backlog, feed p95/query count, WS disconnects nếu đã có hệ metrics; không tự thêm full observability stack.
3. Lập rollout plan cho thay đổi có ảnh hưởng API/schema/caching; feature flags nếu thực sự cần để rollback an toàn.
4. Giữ migration rollback và lưu ý data backup. Không tự chạy production migration/deployment.
5. Nếu di chuyển code làm thay đổi cache keys, event names, serialization, chỉ thực hiện cùng compatibility strategy và tests.

---

# XIV. THỨ TỰ THỰC HIỆN BẮT BUỘC

1. Audit **repository hiện tại**, đối chiếu các rules của ALOUTE V2.3; phân loại `Already Correct` trước khi sửa.
2. Chạy baseline tests, kiểm tra Git, xác lập ownership và dependency map.
3. Tạo/hoàn thiện architecture tests và public module API boundaries tối thiểu.
4. Refactor pilot `notifications`/outbox nếu còn vi phạm.
5. Hoàn thiện `identity`, `users`, `social` và visibility policies.
6. Hoàn thiện `posts` vs `feed`, tách use cases nhưng không viết lại ranking.
7. Hoàn thiện `communities`, `messaging`, `calls` và concurrency protections nếu cần.
8. Hoàn thiện `media`, `moderation`, `administration`.
9. Rà REST/GraphQL/WebSocket/cross-module events, tránh N+1 và privacy leak.
10. Kiểm tra migrations/DB ownership, boundary tests, integration/contract/regression.
11. Ghi documentation, báo cáo kết quả và blockers.

**Cho phép thay đổi thứ tự ít nhất có thể nếu dependency thực tế buộc phải làm như vậy, nhưng phải ghi lý do.**

**Sau mỗi phase:** liệt kê file thay đổi, mục đích, module ownership, các API/schema giữ nguyên, tests đã chạy và kết quả thật. Tiếp tục sang phase tiếp theo nếu không có blocker nghiêm trọng. Một blocker cụ thể không được trở thành lý do dừng mọi công việc độc lập khác.

---

# XV. TIÊU CHÍ NGHIỆM THU (DEFINITION OF DONE)

Chỉ đánh dấu DONE khi có bằng chứng:

1. Các module nghiệp vụ được phân định rõ owner, API công khai và chiều phụ thuộc; không còn cycle mới, và các module đã xử lý không import internals/repository của module khác.
2. Không có dữ liệu bị xóa hoặc format bị thay đổi ngoài migration an toàn đã kiểm chứng.
3. Các endpoint REST, GraphQL hiện hữu và WebSocket events giữ tương thích, bao gồm phân quyền và visibility.
4. Các nghiệp vụ Feed V2.2/V2.3, Chat Reply/Reaction/Edit/Unsend/Pin, Call State và Media Cleanup còn hoạt động đúng nếu đã tồn tại.
5. Bảo toàn notification/outbox delivery + retry/idempotency, không mất hoặc gửi duplicate do refactor.
6. Unit, architecture, integration, contract, security và concurrency tests **đã chạy thật** trong phạm vi môi trường; test không chạy phải ghi rõ.
7. Không tạo N+1/whole-table-in-memory-pagination, không chứng minh hiệu năng bằng suy đoán.
8. Không commit secrets, không sửa production resources, không xóa dữ liệu thật.
9. Có tài liệu kiến trúc, danh sách file thay đổi, cách kiểm tra và rollback từng thay đổi đáng kể.
10. Nếu chưa đạt toàn bộ, báo cáo là `PARTIAL` chứ không tuyên bố hoàn thành.

---

# XVI. MẪU BÁO CÁO CUỐI CÙNG — TIẾNG VIỆT

## 1. Verified Architecture Snapshot

- Java/Spring Boot versions thực tế.
- Database/broker/cache/API stack thực tế.
- Những module đã có trước khi refactor.
- Những vấn đề đã xác minh (không kể vấn đề cũ đã được sửa).

## 2. Module Migration Status

| Module | Trước refactor | Đã thay đổi | Boundary Test | Functional/Integration Test | Trạng thái |
|---|---|---|---|---|---|
| Identity | | | | | |
| Users | | | | | |
| Social | | | | | |
| Posts | | | | | |
| Feed | | | | | |
| Communities | | | | | |
| Messaging/Calls | | | | | |
| Notifications | | | | | |
| Media/Moderation | | | | | |
| Administration | | | | | |

## 3. Dependency / Architecture Violations

| Source Module | Forbidden Dependency | Fix | Test Evidence |
|---|---|---|---|

## 4. Changed Files

- New files.
- Modified files.
- Removed files (nếu có và lý do).
- Database migrations (nếu có).
- Thay đổi API/schema/event, kể cả compatibility adapters.

## 5. Security and Business Rules Preserved

Giải thích rõ quyền Auth, block/mute/private/group, Post/Feed visibility, Chat ownership, notification privacy, call state, media retention và cross-module data access.

## 6. Test Results

| Command/Test Suite | Passed | Failed | Skipped | Lý do/Ghi chú |
|---|---:|---:|---:|---|

Ghi **lệnh thực tế** đã chạy. Nếu thiếu DB/Docker hoặc tài khoản dịch vụ, phân biệt `NOT RUN` với `PASS`.

## 7. Performance

- Baseline / After chỉ khi có số đo.
- Query count, p95/throughput/index plans nếu test được.
- Chỉ rõ điểm chưa verify và rủi ro còn lại.

## 8. Remaining Issues

- P0 — critical/security/data loss.
- P1 — high/important architecture or functional risk.
- P2 — optimization/future improvements.

## 9. Final Assessment

Nêu `DONE`, `PARTIALLY COMPLETE` hoặc `BLOCKED`, mức độ hoàn tất thật sự, lý do, và bước tiếp theo chính xác. Không dùng lời khẳng định chung chung.

---

# XVII. YÊU CẦU THỰC HIỆN CUỐI CÙNG

**Hãy bắt đầu NGAY trên repository đang mở:**

1. In ra tóm tắt audit ngắn, đặc biệt phân biệt version/source hiện tại với những mô tả lịch sử.
2. Chọn một boundary violation có thật, sửa theo vertical slice, chạy tests và công bố các file thay đổi.
3. Tiếp tục thực hiện các phase theo thứ tự, không chỉ xuất tài liệu, không tạo mã giả, không dừng ở folder skeleton.
4. Giữ mọi chức năng đã tồn tại, nhất là Feed, Chat/Call V2.3, security, GraphQL, REST, realtime và dữ liệu.
5. Nếu môi trường hạn chế build/integration test, tiếp tục các phần an toàn khác, ghi rõ phần nào chưa kiểm chứng.
6. Không hỏi xác nhận lại các quyết định đã rõ; chỉ nêu blocker khi cần quyền truy cập, tài nguyên production hoặc một quyết định có rủi ro không thể đảo ngược.

**MỤC TIÊU CUỐI CÙNG:** ALOUTE có backend **Modular Monolith được thực thi thực chất bằng module ownership + public APIs + architecture tests**, giữ tương thích, dữ liệu và quyền riêng tư, sử dụng event/outbox có kiểm chứng, GraphQL có chọn lọc, khả năng test/scale tốt hơn — **không phải chỉ chuyển thư mục hay gắn nhãn kiến trúc mới**.
