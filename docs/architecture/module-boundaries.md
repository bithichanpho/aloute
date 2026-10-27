# Ranh giới module và lộ trình strangler

Đây là migration từng lát cắt, chưa phải toàn backend đã modular hóa. Không dùng `ApplicationModules.verify()` trên legacy rồi bỏ qua lỗi. ArchUnit core 1.5.1 chỉ là test dependency; dùng với JUnit hiện có theo [tài liệu ArchUnit](https://www.archunit.org/userguide/html/000_Index.html). Không thêm runtime/event broker/database.

## Boundary đã khóa

| Owner | Public APIs có consumer thật | Implementation | Dữ liệu sở hữu |
|---|---|---|---|
| notifications | Notifications, NotificationIntents, NotificationCleanup, NotificationAccountCleanup, ReliableNotificationPush | NotificationApplicationService, NotificationOutboxApplicationService, NotificationDeliveryWorker, NotificationCleanupService, NotificationAccountCleanupService | notifications, notification_outbox, user_push_tokens |
| users | UserDirectory | JpaUserDirectory | Scalar username/id projection từ users; không xuất entity/credential |
| social | ProfileVisibility, FollowedAccounts | LegacyProfileVisibility, JpaFollowedAccounts | Active/block policy và follow edges theo keyset |
| posts | PostNotificationLookup, FeedPostReader | NotificationPostReader, PostFeedReader | Post owner/read permission, SQL feed candidates, batch card assembly; không xuất Post entity |
| feed | FeedQuery | FeedQueryService | Bounded traversal/cursor; cache namespace và keys giữ nguyên |
| communities | CommunityReviewAccess | JpaCommunityReviewAccess, CommunityRoles | Role OWNER/MANAGER/legacy ADMIN; current membership |
| messaging | MissedCallHistory | ChatService hiện hữu | CallTimeoutService gọi public messaging port, không đổi state machine |
| media | Chưa migrate toàn bộ API | MediaDeletionPolicy và lifecycle/storage guards | Physical deletion default-off và dry-run default-on |

`notifications.application` chỉ gọi `users.api`, `social.api`, `posts.api`; không gọi User/Post repository hoặc service implementation của module khác. Cross-module APIs không phụ thuộc persistence/entity/service cũ. ArchUnit kiểm tra bytecode, public APIs, cross-module internals và cycles trên các package đã migrate, không tuyên bố legacy graph không có cycle.

Các JPA types `Notification`, `NotificationOutboxEvent`, `INotificationRepository`, `INotificationOutboxRepository`, `IUserPushTokenRepository` vẫn ở package cũ nhưng là **named persistence boundary thuộc Notifications**. Allowlist chỉ 5 tên đầy đủ, không mở toàn `repository`/`model`. Chưa chuyển entity để giữ entity names, associations, schema validation, mixed-version persistence. `Notification.receiver/sender` vẫn liên kết User cũ: phần legacy cần migrate sau, không được coi đã tách toàn bộ domain.

## Compatibility và Spring wiring

- `service.NotificationService`, `NotificationOutboxService`, `NotificationOutboxWorker` là compatibility facades duy nhất có `@Service`; implementation cha không đăng ký component lần hai. Giữ bean names, schedule/lock names, property keys, outbox UUID và JPA mappings.
- `NotificationService` constructor `@Autowired` chỉ nhận public ports và persistence do Notifications sở hữu. Constructor cũ là adapter cho direct Java callers/tests, không được Spring chọn.
- PostService và CommunityDataCleanupService production nhận `NotificationCleanup`, không giữ repository notification làm field. Constructor cũ vẫn adapter tạm thời. Consumer notification chuyển sang `Notifications`, không gọi private implementation.
- AccountSecurityService production nhận `NotificationAccountCleanup`; owner xóa notification/pending intent/push registration trong transaction đã được identity cho phép. Các lệnh xóa account notification/outbox dùng JPQL bulk, không load từng event.
- PostController/ViewController production nhận `FeedQuery` trực tiếp. `FeedQueryService` là primary bean. `PostService implements FeedQuery` chỉ giữ API Java cũ; class này vẫn là legacy facade trộn use cases, **không tuyên bố cả class đã thành Posts module hoàn chỉnh**. Các legacy constructors xây adapter cho direct callers. Không đưa legacy facade vào kết luận rằng toàn graph đã acyclic.
- Không đổi đường REST `/api/notifications`, envelope/DTO, STOMP `/queue/notifications`, FCM type và `/activity`.
- Outbox vẫn delivery at-least-once. Unique `outbox_event_id` chống duplicate notification SQL. Crash sau FCM ACK hoặc WS send trước xóa outbox vẫn có thể gửi lại external event; chưa có end-to-end exactly-once và không tuyên bố điều đó.

## Transaction và quyền

Post deletion đã có PostgreSQL transaction executor. Notification cleanup giờ được gọi **trong transaction đó**, qua owner API với REQUIRED, thay callback sau commit có thể mất khi process crash. JPA và JSONB cùng DataSource; không còn comment sai rằng đó là hai database. Media tombstone giữ nguyên, Cloudinary I/O không đưa vào transaction.

Community cleanup giữ bounded/retry workflow hiện có. API chỉ cho authorized deletion orchestration; không expose endpoint cleanup mới. Numeric community reference chỉ xóa đúng 3 loại notification community đã có; không mở rộng phạm vi xóa.

Worker recheck profile/block và quyền post trước LIKE/COMMENT/REPOST/MENTION/COMMUNITY_POST_OK/COMMUNITY_POST_NO. COMMUNITY_REVIEW chỉ gửi khi post còn PENDING và người nhận hiện vẫn được Communities cho phép review; không gửi cho manager đã bị hạ quyền/rời nhóm. Rule manager dùng chung CommunityRoles với CommunityService, giữ OWNER/MANAGER/legacy ADMIN. Đây là thay đổi bảo mật có chủ đích. Concurrent access change ngay sau check vẫn cần integration/race verification; không hứa atomic authorization với dịch vụ ngoài.

Username directory được nạp một lần cho tối đa 20 actor/receiver IDs của batch 10 events; không lookup entity chứa credential trên mỗi event. Directory failure đưa mọi event về retry/dead-letter theo rule cũ, không xóa intent. Profile/object authorization vẫn được thực hiện riêng cho mỗi event; chưa có số đo DB/p95. Các derived reference-delete methods cũ còn cần tối ưu bulk có integration evidence; không gọi chúng là một SQL bulk query chỉ vì nhận collection.

## Media deletion policy

`app.media.cleanup.physical-deletion-enabled` mặc định false; `app.media.cleanup.dry-run` mặc định true. Chỉ cho phép storage destroy khi cả hai điều kiện opt-in thỏa mãn; phiên này không bật chúng. Lifecycle không đánh dấu DELETED khi dry-run; giữ DELETE_PENDING để audit/retry. Upload/ownership/reference checks không đổi. Các existing lifecycle tests chạy đường opt-in với storage mock; test mới chứng minh mặc định và dry-run không gọi Cloudinary. Chưa xác minh race attach-vs-delete với DB/storage thật.

## Phần chưa migrate

Identity/auth credentials, toàn bộ User write use cases, SocialFeature post edit/report, community-post ownership, messaging/calls state và administration vẫn có legacy dependencies. Adapter `LegacyProfileVisibility` và `NotificationPostReader` còn gọi ContentAccessService nguyên trạng vì policy đó sở hữu các invariant đã được kiểm thử. Không chuyển logic này sang shared để che dependencies. Các legacy constructors là nợ chuyển tiếp được nêu tên, chưa đạt zero-internals cho toàn repository.

## Kiểm chứng và rollback

- `mvn -B test`: toàn bộ unit/contract/architecture tests, không cần production DB.
- `mvn -B verify`: Failsafe integration test tự skip khi thiếu disposable local PostgreSQL variables.
- `deploy/scripts/validate-empty-schema.ps1`: chỉ khi Docker daemon/DB test có sẵn; không dùng database chung.
- Rollback source theo từng lát cắt, build lại artifact; không có schema/data migration cần đảo. Không đổi cache keys, event names hoặc document format. Không push/deploy tự động. Workspace không có Git; SHA manifest và các bản nguồn trước sửa trong `target/architecture-original` hỗ trợ đối chiếu tại phiên này, cần đưa thay đổi vào version control trước rollout.


## Bổ sung boundaries lượt 2

| Owner | API/implementation đang có consumer thật |
|---|---|
| Posts | PostEditing/PostEditingService, immutable PostEditCommand/PostRevisionView, SavedPosts/SavedPostsService, PostCards (PostService facade), PostReviewDispatch (coordinator hiện hữu), PostChanged v1 |
| Posts internal | PostAccess/LegacyPostAccess policy bridge, ContentTags và static ContentTagService facade; own Post/PostRevision/SavedPost models giữ package/storage cũ |
| Identity | ActionRateLimit (AuthRateLimitService), SessionInvalidation/LegacySessionInvalidation; không expose token repository |
| Users | UserDirectory.exists, UserAccountAdministration/JpaUserAccountAdministration; backend ADMIN protection và minimal projections |
| Administration | AccountModeration/AccountModerationService; transaction orchestration Users + Identity qua public APIs |
| Media | PostMediaAttachments do lifecycle hiện hữu implement, không expose ledger entity |
| Moderation | ModerationPolicy do ModerationService hiện hữu implement |
| Feed | FeedInvalidation, FeedPageCacheInvalidator; old FeedCacheInvalidator singleton facade; PostFeedInvalidationListener |

Posts không gọi Feed API ngược chiều: Posts publish PostChanged tối thiểu sau commit, Feed subscribe posts.api. Event chỉ cache best-effort, không durable delivery hoặc exactly-once. Username/token/user body/media không nằm trong event. Privacy-only edit nay global eviction nhưng Redis/crash stale-card fallback còn cần giải quyết riêng.

Scoped architecture gates mở rộng Identity/Moderation/Administration và Posts application; Caffeine chỉ được phép ở Feed infrastructure. Social facade không được gọi User repository hoặc private identity methods, không phụ thuộc SavedPost persistence. Legacy constructors vẫn chỉ là compatibility composition; Post/Comment moderation/report trong SocialFeatureService chưa chuyển owner.

Bulk reference cleanup giữ các legacy long signatures qua default method → int bulk JPQL command. Schema/entity names, nullable reference semantics, typed community filters không đổi. Chưa verify query execution bằng PostgreSQL; NotificationBulkCleanupIT chỉ chạy trên opt-in disposable local DB và rollback mọi assertion transaction.

Lượt 2 giữ nguyên REST controller files, DTO/model/document/resource hashes. Chi tiết tests/rollback: [continuation-report.md](continuation-report.md).


## Bổ sung boundaries lượt 3

FeedQueryService luôn chạy authorization orchestration; internal FeedPageQuery giữ cache/traversal. Chỉ cache miss đã thực thi body mới bỏ qua refresh; mỗi hit dùng posts.api.FeedPostReader.refreshCards để Posts tự reload/authorize/map tối đa 30 IDs, giữ cursor/order. PostService legacy không còn cache wrappers chặn facade. Cache eviction event không phải security guarantee.

Calls nằm dưới messaging.calls vì dùng missed-call history cùng messaging. CallRegistryService primary provider của messaging.api.CallSignaling, chỉ phụ thuộc Users/Notifications public APIs và framework. Legacy CallTimeoutService giữ scheduling và Java facade, ChatController chỉ nhận public port. Users CallParticipants/JpaCallParticipants owns profile lookup, không trả entity/credentials. Legacy facade và primary provider cùng state; không thêm scheduled poll thứ hai.

ArchUnit bổ sung rule strict Calls dependencies (12 rules tổng). Redis keys/scripts/encoded state không đổi; JSON signal field names/destinations giữ nguyên. Runtime WebSocket failures không chặn history/intent, nhưng Redis-to-PostgreSQL durable recovery vẫn là P1. Xem [cache-and-calls-report.md](cache-and-calls-report.md).


## Calls reliability checkpoint — 24/10/2026

Messaging/Calls sở hữu call_sessions qua CallSessions/JdbcCallSessions; ChatService sở hữu JSONB CALL history với CallHistoryAuthorization và database uniqueness. CallCompletionService điều phối Messaging history + notifications.api.NotificationIntents trong cùng PostgreSQL JPA/JDBC transaction, không ghi notification tables trực tiếp. Redis mirror và signaling nằm ngoài durable transaction. Production Spring wiring bắt buộc durable store/completion provider; legacy construction adapters chỉ dùng compatibility tests. Không thêm outbox/broker mới. Chi tiết migrations/rollout, scoped boundaries và actual tests: [backend-bugfix-report.md](../quality/backend-bugfix-report.md).

## Social SQL read interface v1 — Following performance

Social công bố `aloute_read_social_following_authors(viewer_id bigint, value text)` qua migration V20261107,
được đặt tên trong `social.api.FollowedAccounts.READ_VIEW`. Đây là projection đọc hiện thời từ dữ liệu Social,
không materialized copy, không chuyển write ownership của `friends` sang Posts. API Java `hasFollowing`
được `JpaFollowedAccounts` triển khai bằng bounded existence query để tránh quét timeline khi không có follow.

Posts chỉ sử dụng public view qua criteria `inReadView`, không JOIN bảng nội bộ friends hoặc import Social repository/entity.
Criteria chỉ chấp nhận identifier thuộc namespace `aloute_read_*`, luôn bind viewer/self values và không nhận SQL từ HTTP.
Viewer ID do use case xác định từ principal; public view chỉ chọn candidate authors, không thay object authorization.
Feed giữ batch privacy/moderation/block/group checks và dùng `refreshFollowingCards` để kiểm tra lại follow trên cache hit.
View được migrate trước rollout, không ghi dữ liệu hay thêm index; compatibility constructors và API REST/cursor giữ nguyên.
Xem [báo cáo hiệu năng](../performance/aloute-performance-optimization-report.md) cho benchmark/rollback và giới hạn của cách đọc đồng bộ này.
