# Backend audit — selective ZIP merge, 28/10/2026

Nguồn sự thật: worktree sạch tại `c571787` trước khi ghép; ZIP `D:/LTWEB/src.zip` có Git ref cũ `7251dd9` và các thay đổi chưa commit. Nhánh làm việc: `codex/merge-zip-enhancements-20261028`. Không thay source bằng toàn bộ ZIP.

## Verified architecture snapshot

- `pom.xml`: Spring Boot 4.1.1, Java target 21; môi trường chạy kiểm tra JDK 22. Không đổi dependency/build tool. README ở root không tồn tại trong snapshot này.
- Một Spring Boot deployable, REST + Thymeleaf/JavaScript, WebSocket/STOMP và WebRTC. Không có GraphQL dependency/schema hoặc MongoDB dependency được xác nhận trong source này; không tự thêm.
- PostgreSQL qua JPA và `PostgresDocumentStore` JSONB; Flyway. Redis/Caffeine đã có. STOMP relay RabbitMQ tùy cấu hình; TURN, Cloudinary, Firebase, mail và FastAPI moderation là các tích hợp hiện có.
- Đã có các package nghiệp vụ bên dưới, public APIs và ArchUnit; còn legacy adapters trong `service`, `controller`, `repository`, `model`. Đây chưa phải kết luận mọi backend class đã migrate hoàn toàn.

## Ownership và phạm vi thay đổi

| Module/owner | Entrypoint/API thực tế | Persistence/integration | Dependencies/risk | Action |
|---|---|---|---|---|
| Identity | AuthController, SessionInvalidation, ActionRateLimit | JPA tokens/auth; Redis rate limit | Legacy wiring còn tồn tại | Giữ nguyên |
| Users | UserService, UserDirectory, CallParticipants | JPA users | Call lookup qua public API | Giữ username/profile fixes |
| Social | SocialFeatureController, CloseFriendController, ProfileVisibility, CloseFriendships | Follows/block/mute/close-friend; JSONB follow requests | Privacy quyết định tại backend | Giữ các luật đã khôi phục |
| Posts | PostEditing, SavedPosts, PostNotificationLookup | JSONB posts/revisions/saves | PostAccess, media/review public ports | Ghép edit có field presence; giữ CAS và history |
| Feed | FeedQuery, FeedInvalidation | Query/cursor và cache | Public Posts APIs; không ghi post trong slice này | Already Correct, không đổi ranking |
| Communities | CommunityService, CommunityReviewAccess | JPA membership + JSONB content | Role/membership hiện hành | Không thay |
| Messaging/Calls | ChatController, CallSignaling, MissedCallHistory | JSONB messages + PostgreSQL call_sessions; STOMP | Calls gọi messaging/users/notifications APIs | Ghép terminal history, duration, journal retry và ICE |
| Notifications | Notifications, NotificationIntents, workers | JPA notification/outbox, push | Có vi phạm cũ: public API lộ model ModerationStatus | Giữ nguyên outbox; lỗi cũ được ghi nhận |
| Media | PostMediaAttachments, lifecycle services | Cloudinary + owned references | Không được xóa media đang dùng | Giữ references khi edit; không bật cleanup thật |
| Moderation | ModerationPolicy, Java workers, FastAPI routes | Existing review workflow; provider adapter | ZIP có nhánh duyệt thiếu audio/duration | Ghép retry/single-flight, sửa fail-closed |
| Administration | AccountModeration, admin adapters | JPA audit/account ownership | Public users/identity ports | Không thay |

## Phân loại bằng chứng

| Loại | Phát hiện | Xử lý |
|---|---|---|
| Confirmed Bug | ZIP ghi terminal history sau khi finish đã COMPLETED; lỗi/crash không có retry | Dùng journal hiện có cho mọi trạng thái terminal, giao dịch history/completion và MISSED outbox |
| Confirmed Bug | ZIP đóng peer sau lỗi kết nối nhưng không gửi hangup; stale hangup có thể đóng call mới | Gửi hangup, replay khi reconnect, guard callId và async media setup |
| Confirmed Bug | ZIP bỏ qua audio extraction/transcription lỗi hoặc duration không rõ | Trả 503 thay vì verdict; yêu cầu đủ các frame đã lập kế hoạch |
| Confirmed Bug | Edit gửi lại display media URLs có thể đổi/mất media gốc | REST DTO phân biệt omitted/explicit null; frontend gửi content/visibility |
| Architecture Violation | Notifications dùng model enum trong public API/application | Đã xác nhận trên HEAD sạch; chưa refactor trong phạm vi ghép ZIP |
| Performance Risk | Chi phí media và hàng đợi; recovery scan cho terminal states mới | Queue hữu hạn, single-flight; thêm index additive. Không tuyên bố nhanh hơn khi chưa đo |
| Already Correct | Privacy/close friends, unread follow requests, saved/search, reaction removal, profile grid và username links của worktree | Giữ nguyên, không lấy bản cũ từ ZIP |
| Requires Integration Testing | TLS trust trên hai thiết bị, STOMP relay login, TURN/NAT, OpenAI + media hosts, audio/video thật | Chưa xác minh bằng test doubles; cần môi trường riêng phù hợp |

## Baseline và kiểm chứng

Baseline trước chỉnh sửa: targeted 30 test có 2 architecture failure cũ. Sau ghép, suite unit 524 test có 516 đạt, 6 failure, 1 error, 1 skipped. Bảy ca không đạt được tái hiện y hệt trên source HEAD `c571787` sạch trong thư mục build cách ly (29 test chọn đúng bốn class). Không sửa test để che các lỗi này.

Logs và XML nằm trong `target/zip-merge-*`; kết quả chi tiết và phân biệt unit/integration/browser xem `docs/quality/zip-merge-20261028.md`. Không chạy ứng dụng với cấu hình datasource hiện hành, không chạy migration production, không đọc/copy secrets từ ZIP.

Phạm vi lần này là ghép phần mới có kiểm chứng. Trạng thái refactor toàn backend theo tất cả tiêu chí master prompt vẫn là **PARTIAL**, không được suy diễn từ kết quả slice này.
