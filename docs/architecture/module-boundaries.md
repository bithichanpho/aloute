# Boundaries của các slice được ghép từ ZIP

## Posts edit

`SocialFeatureController` → compatibility facade `SocialFeatureService` → `posts.api.PostEditing` / immutable `PostEditCommand` → `posts.application.PostEditingService`.

Posts sở hữu quyền tác giả, revision history, CAS và trạng thái moderation. Qua `identity.api.ActionRateLimit`, `media.api.PostMediaAttachments`, `moderation.api.ModerationPolicy`, `posts.api.PostReviewDispatch` và `PostChanged` để phối hợp. Không đưa entity/repository của module khác vào command mới.

REST `PUT /api/posts/{id}` và response giữ nguyên. Content/media bị bỏ khỏi body được giữ nguyên; explicit null/blank media vẫn có thể xóa như caller full replacement cũ. Default visibility PUBLIC vẫn giữ, visibility null không hợp lệ. Sau khi hợp nhất field, backend từ chối bài trống và URL không thuộc quyền sở hữu; không dùng display URLs từ read response để sửa media gốc.

## Messaging và calls

`ChatController` → `messaging.api.CallSignaling` → `messaging.calls.application.CallRegistryService` → owned `CallSessions` → `JdbcCallSessions`.

`CallCompletionService` → owned call journal + `messaging.api.MissedCallHistory` + `notifications.api.NotificationIntents`. Metadata trạng thái/thời lượng lấy qua `messaging.api.CallHistoryAuthorization`; `DurableCallHistoryAuthorization` đọc owned session store. Calls không truy cập message repository hay notification repository. Participant lookup qua `users.api.CallParticipants`.

`finish`/expiry đặt terminal state và PENDING trong cùng SQL update. Worker claim batch 10, lease/token và row lock; transaction ghi history + (chỉ MISSED) notification outbox + completion. Lỗi rollback để retry có backoff; unique callId giữ tính idempotent. PostgreSQL là nguồn trạng thái; Redis chỉ mirror, không có giao dịch phân tán giả. Callback WebSocket chạy sau commit, không có cam kết exactly-once.

Không thêm event bus/outbox thứ hai. Không thay STOMP destinations, signaling shape hay authorization semantics. `callDurationSeconds` là response field bổ sung nullable để đọc được dữ liệu cũ; server tính thời lượng. Không backfill/replay lịch sử cũ.

## Moderation và cấu hình

FastAPI vẫn là integration đã có, không tách thêm service. Giữ token nội bộ, exact host allow-list cho mỗi redirect, giới hạn kích thước/pixel và fail-closed. Job/cache chỉ chứa verdict theo URL/type, queue hữu hạn; lỗi không cache. HTTP wait hữu hạn khác với deadline hợp tác của job nền; không tuyên bố mọi thao tác là hard real-time.

Profiles `lan-call` và `call-distributed` là opt-in. LAN dùng một backend HTTPS, compose tắt; distributed dùng hạ tầng có sẵn, kiểm tra điều kiện đầu vào. Chúng không đổi default app configuration hoặc xóa RabbitMQ/TURN trong Compose. Helper LAN chỉ có tác động khi người dùng tự chạy; chưa được chạy trong quá trình merge.

## Architecture gates và phần còn tồn tại

`ModuleBoundaryTest` giữ nguyên, không suppress. Các quy tắc no cycles, cross-module public APIs, Calls application ownership và Posts collaborators đã chạy; hai rule Notifications vẫn fail do enum model đã có trên HEAD trước merge. Không đánh dấu toàn bộ backend đã đạt Modular Monolith hoàn chỉnh.

Danh sách file, evidence và rollback của lần ghép nằm trong `docs/quality/zip-merge-20261028.md` và `CALLING_SETUP.md`.
