# ADR 0001 — Public APIs cho từng lát cắt, giữ compatibility facades

Ngày: 24/10/2026. Trạng thái: Implemented / Partially Tested.

## Bối cảnh

Source là một Spring Boot 4.1.1 deployable với PostgreSQL JPA/JSONB, không có GraphQL hoặc MongoDB. Legacy controller/service/repository chứa nghiệp vụ và nhiều regression tests. Durable notification outbox, ShedLock và worker đã tồn tại. Không có Git metadata, Docker daemon hay disposable DB trong môi trường này.

## Quyết định

1. Không chuyển database/broker/framework hoặc xây outbox thứ hai. Public internal APIs phục vụ synchronous queries/authorization; delivery vẫn dùng outbox hiện có. Không coi Spring event là durable delivery và không thêm GraphQL khi chưa có consumer cần thiết.
2. Tách implementation Notifications và Feed orchestration theo owner. Các legacy facades/constructors duy trì direct Java callers và bean names. Controller production gọi public API; Spring wiring tests kiểm tra không có worker duplicate và transaction interceptor thực sự được áp dụng.
3. Chưa move JPA entities hay đổi schema cùng lần refactor này. Architecture test khóa các package đã chuyển và exact owned persistence types; các bridge legacy được ghi rõ, không suppress toàn hệ thống hoặc tuyên bố toàn backend đã modular.
4. Notification cleanup trong transaction Post deletion dùng cùng PostgreSQL DataSource. Account notification/push cleanup đi qua owner. Chỉ các deletion flows đã có và đã authorization mới gọi cleanup; không thêm background cleanup tự xóa dữ liệu.
5. Recheck quyền tại thời điểm delivery; public ports trả scalar/record/response contract, không trả managed entity. Feed chuyển traversal/cursor ra khỏi PostService, còn Posts chịu trách nhiệm query + batch authorization + mapper.
6. Physical media deletion cần opt-in và dry-run-off. Trạng thái ledger không báo xóa thành công khi chưa gọi storage.

## Hệ quả và giới hạn

Có thêm các API với consumer thật, không thêm deployable/infrastructure. Cần tiếp tục migrate legacy identity/users writes, SocialFeature post editing, community-post cleanup, messaging/calls, moderation và admin. PostService còn là compatibility facade trộn ownership, chưa đạt zero legacy imports. SQL notification rows dedupe theo outbox_event_id; FCM/WS vẫn có thể redeliver sau crash. Missed-call persistence/notification có khoảng trống sau Redis timeout claim; đây là P1 còn tồn tại, không được gọi là đã bảo đảm durable.

Rollout bằng artifact sau khi kiểm tra disposable PostgreSQL, Redis/RabbitMQ và các adapter test account. Rollback source theo slice, không rollback schema vì không có migration mới. Không push/merge/deploy trong phiên này.


## Cập nhật lượt 2

Post editing/history/Saved Posts và account moderation đã có owner APIs/implementation/wiring/tests. PostChanged v1 là in-process cache hint tối thiểu, không được coi như outbox/durable event. Administration điều phối User state và Identity revocation trong cùng PostgreSQL transaction. Bulk notification reference cleanup dùng JPQL commands với legacy default adapters. Các ownership còn lại và integration gaps được cập nhật trong continuation-report.md và backend-refactor-report.md; quyết định không big-bang/schema migration giữ nguyên.


## Cập nhật lượt 3: cache authorization và Calls

Không dùng TTL/eviction best-effort làm authorization. Giữ cache payload/key compatibility, rehydrate + authorize trong Posts trên cache hit; per-request body marker tránh pre-cache lookup race và duplicate miss hydration. Chi phí DB trên hit cần đo sau, privacy ưu tiên hiện tại.

Calls là submodule messaging.calls với primary CallRegistryService và public CallSignaling; legacy facade giữ CallInfo/constructors/scheduler compatibility. Profile lookup thuộc Users, các side effects chỉ qua Messaging/Notifications APIs. Giữ Lua/state format và không tuyên bố delivery durable từ Redis claim; isolation WebSocket chỉ sửa lỗi gián đoạn trong cùng tiến trình.
