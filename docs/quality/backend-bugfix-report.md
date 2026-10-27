# ALOUTE — Backend Bug Fix, Reliability & Regression Testing

Ngày kiểm chứng: 24/10/2026. **Kết luận: PARTIALLY VERIFIED, không tuyên bố production-ready.** Các luồng bug fix chính đã IMPLEMENTED, UNIT TESTED và INTEGRATION VERIFIED trên môi trường tạm; topology/dịch vụ production chưa nghiệm thu.

## 1. Tổng quan

### Source và kiến trúc đã xác minh

- Baseline lượt này: 325 Java production files, 104 test classes sau refactor trước; hiện tại 330/109. Không có Git metadata; giữ nguyên công việc trước đó và dùng SHA-256 checkpoints. Mốc gốc: `D:/aloute-develop123/aloute-quality-checkpoints/20261024/baseline-hashes.json`.
- Java release 21, Spring Boot 4.1.1, Windows build/test JDK 22; Maven Wrapper 3.9.16. PostgreSQL JPA + JDBC JSONB cùng DataSource/JpaTransactionManager, Redis, RabbitMQ STOMP, REST/Thymeleaf/WebSocket/WebRTC. Repository không có GraphQL/MongoDB; không thêm.
- Test environment: PostgreSQL 18.3, Redis 8.0.5, Nginx 1.28.3 lấy từ Ubuntu packages và giải nén vào runtime riêng trong WSL, không cài thành system services. Chỉ bind loopback, database có prefix `aloute_quality_`; không dùng database/credentials của dự án. Docker daemon vẫn không kết nối được.
- 80 DTO/model source hashes giữ nguyên. Không thay REST paths/methods/DTO, STOMP destinations hoặc SDP/offer/answer/ICE fields; không đổi framework/frontend CSS, database engine, JSONB keys hay message ID format. App.js chỉ đổi hai URL thư viện realtime sang tài nguyên nội bộ cùng phiên bản.

### Audit phân loại và quyết định

| Vấn đề hiện tại | Phân loại | Quyết định |
|---|---|---|
| Redis claim xóa dấu vết trước history/outbox; failure chỉ log | CONFIRMED BUG / DATA INTEGRITY RISK | PostgreSQL authoritative sessions + recovery leases; không còn destructive Redis timeout claim trong production path |
| History check-then-insert không có callId uniqueness | DATA INTEGRITY RISK | Unique partial index + INSERT ON CONFLICT DO NOTHING + canonical participant validation |
| Client missed flag chi phối notification; hangup send có thể chặn enqueue | SECURITY ISSUE / CONFIRMED BUG | Backend terminal state; history/outbox transaction; signaling best-effort sau quyết định bền vững |
| Block/unfriend có thể ngăn participant kết thúc call | CONFIRMED BUG | Cho registered participant kết thúc; suppress signal khi mất peer visibility |
| Dùng lại callId đã có trong history cũ | DATA INTEGRITY RISK | Reserve historical IDs bằng terminal completed rows, không replay notification |
| SockJS/STOMP tải từ CDN bị script-src chặn | CONFIRMED BUG | Self-host đúng bản đang dùng, license + pinned hashes; giữ CSP |
| Timeout WebSocket exception chặn history | ALREADY FIXED ở lượt trước | Giữ isolation; production mới commit history/intent trước timeout signaling |
| Notification outbox retry/dead-letter/SQL dedupe | ALREADY FIXED | Tái sử dụng provider/API hiện có; không dựng outbox/broker khác |
| Maven Wrapper thiếu properties | ALREADY FIXED trong source hiện tại | Properties và scripts đã đủ; chạy Windows/Linux thành công, không sửa wrapper |
| Compose trỏ Nginx file không tồn tại | ALREADY FIXED trong source hiện tại | File có thật và hash không đổi; config parse/nginx -t pass, không thay file |
| Test JVM options bị lẫn với late-bound coverage argLine | CONFIRMED TEST ENVIRONMENT ISSUE | Thêm testJvmArgs cho CLI, giữ @{argLine}/Mockito agent; xác minh JVM timezone khi chạy DB tests |
| Live RabbitMQ relay/TURN/Firebase/Cloudinary, deployment/mixed versions | REQUIRES INTEGRATION TEST | Chưa có runtime/credentials thử nghiệm tương ứng; không deploy hoặc nới security |
| Query plans/p95/load/throughput | PERFORMANCE RISK / NOT VERIFIED | Không load test hoặc suy đoán capacity |

Audit ban đầu đã nhầm file Nginx chưa có; đối chiếu lại xác nhận nó có trước lượt này. Không giữ kết luận cũ, không khôi phục hoặc sửa thành phần đang đúng.

### Implementation và transaction boundary

1. `CallRegistryService` production luôn nhận `CallSessions` và `CallCompletionService` qua constructor Spring bắt buộc. PostgreSQL giữ RINGING/ACTIVE/ENDED/DECLINED/MISSED/CANCELLED, deadline và terminal state. Redis là mirror best-effort; mất Redis không làm mất durable recovery. Constructor cũ chỉ dành cho local compatibility tests; không cho bật distributed mode khi thiếu durable store.
2. `JdbcCallSessions` chuyển state bằng SQL predicates trên cùng row, DB clock, và SKIP LOCKED. Timeout scan tối đa 100, claim tối đa 10 jobs; lease 60 giây, token chống stale completion, retry exponential tối đa 1 giờ và 20 attempts. Hết attempts chuyển FAILED, kể cả crash ở attempt cuối; bản ghi không bị xóa. ACTIVE giữ expiry 2 giờ như call registry cũ.
3. `CallCompletionService.complete` là transaction PostgreSQL ngắn: lock đúng lease → Messaging history → Notifications public outbox API → COMPLETED. JPA và JDBC dùng cùng transaction manager/DataSource. Không Redis/Cloudinary/Firebase/WebSocket I/O trước commit. History WebSocket callbacks chạy sau commit; timeout signal chạy sau khi transactional provider trả về.
4. Crash trước commit rollback cả history/outbox/marker. Crash sau commit để lại đúng một history + outbox intent + COMPLETED. Worker mới claim lease hết hạn; concurrent/stale workers không tạo completion khác. Không dựa vào synchronized JVM để bảo đảm production concurrency.
5. History có unique callId index chỉ áp dụng CALL documents. `insertIfAbsent` không làm abort transaction khi retry trùng; không update lịch sử thắng trước đó. Production `CallHistoryAuthorization` chỉ cho ghi MISSED đúng caller/callee trong authoritative ledger. Fake IDs, sai participants hoặc RINGING đều bị từ chối.
6. Caller hangup trước deadline → CANCELLED; callee decline → DECLINED; active hangup → ENDED; unanswered deadline → MISSED. Chỉ MISSED sinh history/notification như chức năng visible trước đây. Client missed/callerUserId không quyết định kết quả. Registered participant có thể dọn state sau block/unfriend nhưng không gửi signal vượt peer visibility. Notification worker vẫn recheck quyền khi delivery muộn.
7. `CallSignaling`, `MissedCallHistory`, `CallHistoryAuthorization` và `NotificationIntents` là seams thực tế. Calls chỉ ghi call_sessions; Messaging ghi history, Notifications ghi outbox. Scoped architecture rules không bị suppress/nới cho foreign internals.

Không cam kết exactly-once external delivery. SQL history/outbox/notification dedupe được kiểm chứng; FCM/WebSocket vẫn có thể redeliver khi crash ở provider acknowledgement boundary.

## 2. Bug Fix Status

| Issue | Root Cause | Fix | Tests | Status |
|---|---|---|---|---|
| Missed Call Loss | Redis-only claim xóa state trước durable writes | SQL sessions/leases, retry/FAILED, atomic completion | Real PostgreSQL rollback, restart, 3 dedicated JVM fault points và hai JVM recovery đồng thời | IMPLEMENTED / INTEGRATION VERIFIED |
| Duplicate Call History | Query rồi insert không khóa business key | Unique index + atomic insert-if-absent + guard + reserve old IDs | Concurrent DB inserts, independent JVMs, duplicate migration guard và old-ID reservation | IMPLEMENTED / INTEGRATION VERIFIED; production data preflight chưa chạy |
| Hangup Notification | Client missed flag và inline send/enqueue | Backend terminal state; existing outbox cùng transaction; isolate send | Caller/callee/active/timeout, fake identity, blocked cleanup, double hangup, rollback/crash tests | IMPLEMENTED / UNIT TESTED / INTEGRATION VERIFIED |
| CSP / WebSocket | CDN scripts ngoài CSP allowlist | Same-version local assets + SockJS iframe URL local | Hash unit test + Edge/Playwright real WS under default CSP, reconnect/multi-tab/user isolation/anonymous rejection | IMPLEMENTED / INTEGRATION VERIFIED trong single-node harness |
| Maven Wrapper | Source hiện tại đã có scripts/properties; Windows test PATH thiếu PowerShell | Process PATH sửa cho test; wrapper files giữ nguyên. POM thêm testJvmArgs | mvnw.cmd -v, ./mvnw -v, Windows wrapper clean verify | ALREADY FIXED / VERIFIED; Linux full build NOT RUN |
| Docker / Nginx | Source hiện tại đã có file đúng | Giữ Compose/nginx files, kiểm bằng dummy env và nginx parser thật | docker compose config --quiet, nginx -t | CONFIG VERIFIED; container startup BLOCKED thiếu Docker daemon |
| Authorization | Terminal result/client flags và forged/reused IDs | SQL participant/deadline/state predicates, history guard, revoked-peer signal suppression | Unit contract negatives, actual DB guard, websocket account guard/browser isolation | IMPLEMENTED / UNIT TESTED / INTEGRATION VERIFIED trong phạm vi nêu trên |
| Module Boundaries | Cần durable Calls provider thay private cross-owner writes | Calls-owned JDBC store, Messaging/Notifications public APIs, mandatory Spring wiring | 12 scoped ArchUnit rules + full backend context + transaction integration | VERIFIED cho luồng đã sửa; toàn bộ legacy migration vẫn PARTIAL |

## 3. Changed Files

Lượt này: 20 file mới, 14 file sửa; không xóa source file. Wrapper/Docker/nginx files đã kiểm chứng nhưng không sửa.

| Loại | File |
|---|---|
| Modified | `docs/architecture/backend-audit.md` |
| Modified | `docs/architecture/backend-refactor-report.md` |
| Modified | `docs/architecture/module-boundaries.md` |
| New | `docs/quality/backend-bugfix-report.md` |
| Modified | `pom.xml` |
| Modified | `src/main/java/com/aloute/config/WebSocketConfig.java` |
| Modified | `src/main/java/com/aloute/controller/ChatController.java` |
| New | `src/main/java/com/aloute/messaging/api/CallHistoryAuthorization.java` |
| Modified | `src/main/java/com/aloute/messaging/api/CallSignaling.java` |
| New | `src/main/java/com/aloute/messaging/calls/application/CallCompletionService.java` |
| Modified | `src/main/java/com/aloute/messaging/calls/application/CallRegistryService.java` |
| New | `src/main/java/com/aloute/messaging/calls/application/DurableCallHistoryAuthorization.java` |
| New | `src/main/java/com/aloute/messaging/calls/domain/CallSessions.java` |
| New | `src/main/java/com/aloute/messaging/calls/infrastructure/JdbcCallSessions.java` |
| Modified | `src/main/java/com/aloute/persistence/postgres/document/PostgresDocumentStore.java` |
| Modified | `src/main/java/com/aloute/service/ChatService.java` |
| New | `src/main/resources/db/migration/V20261103__durable_call_sessions.sql` |
| New | `src/main/resources/db/migration/V20261104__call_history_idempotency.sql` |
| New | `src/main/resources/db/migration/V20261105__reserve_historical_call_ids.sql` |
| Modified | `src/main/resources/static/js/app.js` |
| New | `src/main/resources/static/vendor/REALTIME-NOTICE.txt` |
| New | `src/main/resources/static/vendor/sockjs-client/LICENSE` |
| New | `src/main/resources/static/vendor/sockjs-client/sockjs.min.js` |
| New | `src/main/resources/static/vendor/stomp/LICENSE.txt` |
| New | `src/main/resources/static/vendor/stomp/stomp.min.js` |
| New | `src/test/browser/realtime-smoke.cjs` |
| New | `src/test/java/com/aloute/FullBackendContextIT.java` |
| New | `src/test/java/com/aloute/RealtimeDependencyTest.java` |
| Modified | `src/test/java/com/aloute/config/ScheduledJobLockingTest.java` |
| Modified | `src/test/java/com/aloute/controller/CallSignalingContractTest.java` |
| New | `src/test/java/com/aloute/messaging/CallHistoryMigrationIT.java` |
| New | `src/test/java/com/aloute/messaging/CallRecoveryIT.java` |
| Modified | `src/test/java/com/aloute/messaging/CallRegistryWiringTest.java` |
| New | `src/test/java/com/aloute/realtime/RealtimeSmokeIT.java` |

### Migrations, preflight và dữ liệu cũ

- `V20261103`: expand call_sessions/recovery indexes; không thay hay xóa chat records.
- `V20261104`: kiểm tra lịch sử CALL trùng callId rồi tạo unique partial index. Nếu trùng, migration dừng và giữ nguyên dữ liệu; không âm thầm chọn/xóa bản ghi.
- `V20261105`: kiểm tra historical call identities/participants, reserve business IDs thành terminal COMPLETED để không reuse/replay. Không tạo lại history/notification hoặc sửa payload cũ.

Trước production rollout: backup và chạy read-only preflight trên bản sao dữ liệu được cấp quyền. Ví dụ kiểm tra counts (không cần xuất payload/private content):

```sql
SELECT payload->>'callId' AS call_id, count(*)
FROM app_document_records
WHERE collection_name='chat_messages' AND payload->>'messageType'='CALL'
  AND NULLIF(payload->>'callId','') IS NOT NULL
GROUP BY payload->>'callId' HAVING count(*)>1;
```

Nếu có duplicate/corrupt identities: dừng rollout, giữ backup + audit mapping, phân biệt duplicate retry với nhiều cuộc gọi dùng lại ID; thiết kế repair/backfill có review cho từng nhóm trước khi tiếp tục. Không tự delete/archive/re-key dữ liệu người dùng, không bỏ guard hoặc tự sửa Flyway history. Phiên này không truy cập production, vì vậy không khẳng định dữ liệu production đã sạch.

Các test tạo DB ngẫu nhiên `aloute_quality_duplicates_*` / `aloute_quality_historical_*`, kiểm không mất records khi migration bị từ chối rồi chỉ drop đúng DB tạm mà chính test đã tạo. Không drop database ứng dụng hoặc chạy cleanup production.

## 4. Test Results

| Test Suite / Command | Passed | Failed | Skipped |
|---|---:|---:|---:|
| Baseline `mvn -o -B ... test` | 383 | 0 | 1 |
| Initial Calls regression gate | 29 | 0 | 0 |
| Initial PostgreSQL IT attempts | 0 | 3 errors/lần | 0 |
| PostgreSQL/JPA/JDBC gate sau sửa test JVM args | 12 | 0 | 0 |
| Expanded unit/boundary + PostgreSQL/Redis gates | 19 + 13 | 0 | 0 |
| Full context + browser + migration gate | 3 | 0 | 0 |
| Independent JVM recovery + migration gate trước mở rộng crash probes | 16 | 0 | 0 |
| **Final wrapper clean verify — unit/architecture** | **386** | **0** | **1** |
| **Final wrapper clean verify — integration** | **22** | **0** | **0** |
| `mvnw.cmd -v` / `./mvnw -v` | VERIFIED | 0 | — |
| `docker compose ... config --quiet` / `nginx -t` | VERIFIED | 0 | — |

**BUILD SUCCESS**, executable JAR built; Surefire tổng 387 (1 skip), Failsafe tổng 22. JAR đã kiểm có 3 migrations/local bundles mới và không chứa test/browser harness classes. Không đổi Java release hoặc Spring Boot/dependencies.

- Unit skip duy nhất: AlouteApplicationTests.contextLoads yêu cầu DB_URL, không cung cấp biến đó để tránh dùng config DB không kiểm soát. `FullBackendContextIT` đã chạy riêng production composition bằng disposable URL + test-only configuration và PASS, không mock Calls/DB/outbox providers.
- Các lần IT đầu fail trước assertions vì PostgreSQL runtime tách riêng thiếu timezone alias Asia/Saigon và CLI argLine không truyền timezone đến JVM như dự kiến. POM giữ late-bound coverage argLine, thêm testJvmArgs cho CLI; chạy UTC ở test JVM rồi toàn gate PASS. Không chỉnh expected behavior để che production failures.
- Coverage/JaCoCo profile chưa chạy; giữ late-bound property không đồng nghĩa đã kiểm chứng mọi coverage tool.
- Linux JDK 21.0.11-ea chỉ dùng xác minh wrapper launcher; full clean build/test chạy Windows JDK 22 với release 21.
- PostgreSQL/Redis runtime được giải nén cùng dependencies riêng; Nginx parser cần đủ runtime directories và quyền bind 80 như container master. Không sửa OS/deployment để bỏ security, không nới TLS/CSP.
- Browser test dùng app.js, production WebSocketConfig/JwtHandshakeInterceptor/AccountWebSocketGuard và CSP mặc định trên embedded server; fixture JWT/user directory chỉ dùng test cookie. Không tính đây là live OAuth/Firebase hoặc toàn bộ UI Chat/WebRTC device testing.
- CallRecoveryIT dùng PostgreSQL/JPA/JDBC history + notification outbox thật; profile directory và network adapters là test doubles. Có Redis server thật + endpoint outage simulation. Probe JVM riêng halt ở mã 42 khi history chưa commit và 43 sau commit trước signaling; hai JVM khác phục hồi, xác minh đúng một history/intent. Không chỉ dùng mocks/synchronized để khẳng định durable/idempotent.

Log paths:

- `target/quality-clean-verify.log` (bản bền ngoài target: `D:/aloute-develop123/aloute-quality-checkpoints/20261024/quality-clean-verify.log`).
- `target/quality-browser-smoke.log`, `target/quality-call-worker-*-claim-only.log`, `target/quality-call-worker-*-crash-after-history.log`, `target/quality-call-worker-*-crash-after-commit.log`.
- Earlier gates: quality-baseline.log, quality-calls-initial.log, quality-postgres-it*.log, quality-call-redis-it.log, quality-context-browser-migration.log, quality-process-recovery.log; đã lưu lại ngoài target trước clean.

Lệnh full thực tế (PowerShell; test URLs/ports/browser runtime được đặt trong environment của process, không đọc .env dự án):

```powershell
$env:TEMP='D:\aloute-develop123\aloute-quality-checkpoints\20261024\test-temp'
$env:TMP=$env:TEMP
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/20261024/test-temp'
.\mvnw.cmd -o -B '-DtestJvmArgs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:ReservedCodeCacheSize=64m -XX:ActiveProcessorCount=2 -Djava.io.tmpdir=D:/aloute-develop123/aloute-quality-checkpoints/20261024/test-temp -Duser.timezone=UTC' clean verify
```

Cần `ALOUTE_SCHEMA_VALIDATION_URL/USERNAME/PASSWORD` trỏ disposable local database `aloute_quality_*`, `ALOUTE_REDIS_VALIDATION_PORT`, `ALOUTE_BROWSER_EXECUTABLE`, `ALOUTE_PLAYWRIGHT_MODULE`. Không trỏ test tới production. Browser runtime/Node chỉ phục vụ test, không thêm dependency runtime backend.

Compose kiểm bằng `docker compose --env-file <dummy-test-env> -f docker-compose.yml config --quiet`, không load/in .env dự án. Nginx dùng `nginx -t -c <test-root-config>` import nguyên deploy/nginx/docker.conf; không start proxy/deploy service. Wrapper/Docker/nginx files hash khớp baseline.

## 5. Remaining Issues

### P0 — Critical

Không xác nhận P0 còn mở trong các luồng được kiểm chứng. Không coi đó là kết quả pentest toàn hệ thống.

### P1 — High priority

- Live production topology chưa kiểm chứng: nhiều app nodes + RabbitMQ relay, TURN/WebRTC media, Firebase/Cloudinary/provider failure acknowledgement và public HTTPS/WSS/proxy trust. Docker daemon không hoạt động; không có service credentials thử nghiệm cho các tích hợp ngoài. Không tự start/deploy production.
- **Không rolling deploy lẫn phiên bản Redis-only cũ và SQL-authoritative mới.** Cần maintenance/drain active calls, stop old call processors rồi switch đồng bộ. Không import/bỏ dữ liệu Redis active một cách suy đoán. Rollback binary cũng phải drain và giữ compatible recovery processor cho pending jobs; không tự drop tables/indexes hoặc bỏ pending history.
- Unique-index migration hiện dùng transactional CREATE INDEX trên bảng JSONB chung; cần maintenance window và đo lock/build time trên bản sao production trước rollout. Chưa đo large-dataset DDL latency, không coi fresh-schema test là bằng chứng zero-downtime.
- Production duplicate/invalid historical data preflight chưa chạy. Migration chủ động fail safe; cần backup/review nếu phát hiện records bất thường.
- SQL notification dedupe/one intent đã chứng minh; external push/WS vẫn at-least-once, không bảo đảm exactly-once qua provider acknowledgement crash.

### P2 — Improvement

- Chưa đo p95/throughput/SQL plans/backlog capacity hay so sánh hiệu năng trước/sau. Không tuyên bố chịu một số lượng concurrent users cụ thể.
- Cần vận hành giám sát FAILED/RETRY/backlog và reviewed manual replay; không dựng monitoring stack mới. Truy vấn read-only tham khảo:

```sql
SELECT processing_status, count(*), min(ended_at)
FROM call_sessions WHERE state='MISSED' AND processing_status<>'COMPLETED'
GROUP BY processing_status;
```

- Retention/archiving call ledger, account deletion policy và live mobile/full Chat UI/refresh-device behavior cần kiểm chứng riêng; không tự xóa history/ledger để giảm dung lượng.
- Các legacy ownership issues ngoài luồng Calls/History/Notifications của đợt này vẫn nằm trong chương trình architecture refactor; không đánh dấu toàn backend đã migrate hoàn tất.

## 6. Production Readiness

| Mặt đánh giá | Bằng chứng | Kết luận |
|---|---|---|
| Data integrity | Unique constraint, insert conflict handling, preserved historical messages, guards | INTEGRATION VERIFIED trên disposable PostgreSQL; production preflight pending |
| Call reliability | Durable journal, expired leases, bounded retry/FAILED, actual process crash/restart/concurrent JVMs | INTEGRATION VERIFIED trong phạm vi test |
| Realtime reliability | Browser CSP, native WS/STOMP, reconnect, multi-tab/account isolation | VERIFIED single-node harness; relay/TURN/live devices chưa nghiệm thu |
| Security | Backend identity/terminal state, participant guard, blocked cleanup/privacy, auth regression | UNIT/INTEGRATION VERIFIED cho cases nêu trên |
| Database concurrency | Actual PostgreSQL uniqueness/row predicates/leases, JPA+JDBC rollback/commit | INTEGRATION VERIFIED |
| Deployment | Windows clean build, Linux wrapper, Compose parse, nginx syntax | PARTIALLY VERIFIED; chưa chạy full containers/prod rollout |
| Regression coverage | 386 unit/architecture + 22 IT pass; 1 original skip riêng | VERIFIED theo suite/logs; không coi skip là pass |
| Rollback readiness | Source checkpoint/patch; expand-only schema, no automatic destructive down migration | Plan/documented; production backup/drain cần operator thực hiện |

**PARTIALLY VERIFIED — không READY cho production chỉ từ các tests này.** Đã hoàn thành các phần code/kiểm thử an toàn có thể làm trong môi trường hiện tại, không push/merge/deploy và không chạy migration/cleanup trên dữ liệu thật.

Review/rollback source: `target/backend-bugfix.patch` và bản sao ngoài target tại checkpoint; `target/quality-change-manifest.json` có SHA trước/sau. Snapshot công việc architecture cũ và logs đã backup ngoài target trước clean. Không copy rollback đè thay đổi mới; ưu tiên roll-forward nếu đã có pending durable calls. Giữ tables/indexes/data khi rollback application, không tự chạy destructive down migration.

Sau khi verify/artifact checks hoàn tất, đã dừng đúng PostgreSQL data directory và Redis PID của task (kiểm identity trước stop), giữ test DB files và scripts trong checkpoint để tái lập; không dừng dịch vụ/Java process của người dùng.
