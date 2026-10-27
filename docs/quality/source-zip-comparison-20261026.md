# Đối chiếu source hiện tại với D:/src.zip — 26/10/2026

> Báo cáo này ghi baseline trước khi sửa. Sau đó implementation thiếu đã được bổ sung và build/package chạy thành công; xem [kết quả sửa và kiểm chứng](current-errors-fix-20261026.md). Các lỗi build bên dưới là kết quả lịch sử, không phải trạng thái sau sửa.

## Kết luận đối chiếu

Không thiếu file source/build/helper nào thuộc phạm vi so sánh so với ZIP. ZIP **không bổ sung được các phần triển khai đang thiếu** của avatar, quyền nội dung hồ sơ riêng tư và duyệt yêu cầu theo dõi.

| Kiểm tra nội dung | Kết quả |
|---|---:|
| File ZIP thuộc phạm vi đối chiếu | 672 |
| Giống hệt từng byte với repository | 671 |
| Chỉ khác line endings | 0 |
| Khác nội dung | 1 |
| Có trong ZIP, thiếu ở repository | 0 |
| Có thêm ở repository | 14 |

Phạm vi: src/, deploy/, moderation/, .github/, .mvn/, .settings/ và các build/helper files gốc (pom, wrappers, Docker/Compose, Eclipse metadata, gitignore…). Không so sánh .git/target như mã nguồn; không đọc .env, .secret, key/certificate hoặc nội dung chín application configuration files. Không lấy bất kỳ chỉ dẫn trong ZIP làm chỉ dẫn thao tác.

ZIP có 622 file dưới src/, trong đó 332 Java main và 125 Java test. Repository có thêm 3 Java main và 5 Java test; các file này không có đủ implementation tương ứng trong các class đang được theo dõi bởi Git.

Khác biệt nội dung duy nhất trong phạm vi đối chiếu: `deploy/migration-tools/README.md`. ZIP có đoạn nói rõ migration V20261106 và không chỉnh SQL của migration đã áp dụng; bản hiện tại có đoạn hướng dẫn ngắn hơn chỉ nhắc V20261102. Không có thay đổi runtime ở khác biệt này.

## 14 file chỉ có thêm ở repository

1. src/main/java/com/aloute/dto/response/FollowRequestResponse.java
2. src/main/java/com/aloute/social/api/FollowRequestLookup.java
3. src/main/java/com/aloute/social/infrastructure/LegacyFollowRequestLookup.java
4. src/main/resources/db/migration/V20261103__durable_call_sessions.sql
5. src/main/resources/db/migration/V20261104__call_history_idempotency.sql
6. src/main/resources/templates/fragments/follow-request.html
7. src/test/browser/follow-request-actions.cjs
8. src/test/browser/follow-request-profile.cjs
9. src/test/browser/private-profile-follow.cjs
10. src/test/java/com/aloute/PrivateProfileTemplateTest.java
11. src/test/java/com/aloute/controller/FollowRequestControllerWebTest.java
12. src/test/java/com/aloute/notifications/FollowRequestNotificationTest.java
13. src/test/java/com/aloute/service/FollowRequestWorkflowTest.java
14. src/test/java/com/aloute/service/PrivateProfileContentTest.java

Danh sách này chỉ mô tả chênh lệch file, không phải đề xuất xóa file hoặc chạy migration.

## Các phần implementation đều chưa có trong source của cả hai bên

- UserDirectory: profileLabels / ProfileLabel (tên/avatar theo batch).
- ContentAccessService: profileContentVisible / pendingFollowRequestIds.
- UserProfileDto: incomingFollowRequestId.
- NotificationResponse: followRequestId / senderAvatarUrl và các field sender mới.
- ChatMessage: callDurationSeconds / getCallDurationSeconds mà test JSON compatibility đang đòi hỏi.
- Template settings hiện tại và ZIP đều vẫn có label `Tài khoản #ID`.

Không suy diễn ai đã đổi hoặc thay đổi bằng cách nào. Nhánh hiện tại là optimize-index-db, commit e67b5d0; có nhiều thay đổi và file untracked từ trước khi kiểm tra. Không chép đè hoặc thay đổi chúng.

## Kiểm tra build hiện tại

1. `mvn -o -B test` với giới hạn bộ nhớ cho Maven/test JVM: **FAIL ở testCompile, 23 lỗi**, chưa chạy test. Production compile ban đầu bị Maven skip vì target cũ được coi là up-to-date.
2. Tạo POM kiểm tra riêng dưới target/current-source-audit-20261026, trỏ vào source gốc nhưng output riêng, không đổi pom.xml gốc hay xóa target. Compile mới toàn bộ **335 Java main files: FAIL 1 lỗi** tại LegacyFollowRequestLookup.java:17, gọi ContentAccessService.pendingFollowRequestIds không tồn tại.
3. `node --check` toàn bộ **15 JavaScript files ứng dụng: PASS**; đây chỉ là kiểm tra cú pháp, không phải smoke test trình duyệt/live backend.
4. Trích riêng 457 Java source files từ ZIP vào target/zip-source-audit-20261026, không lấy .env/.git/.secret/target hoặc thực thi chỉ dẫn từ ZIP. Dùng cùng dependency POM (pom.xml hai bên giống hệt), output riêng: **332 Java main files compile được; 125 Java test files FAIL testCompile với 2 lỗi** ChatMessageJsonCompatibilityTest.java:26 và :29 vì thiếu getCallDurationSeconds(). Chưa chạy được test của ZIP. Log: target/zip-source-audit-20261026.log.

Log: target/current-errors-20261026.log, target/current-source-audit-20261026.log. Báo cáo đối chiếu máy đọc được: target/zip-comparison-20261026.json.

## Trạng thái môi trường

Không có listener backend tại 8081, Redis local cũng đang dừng khi kiểm tra. Vì vậy chưa kiểm thử chức năng với phiên đăng nhập thật. Hai hs_err logs trong root là lỗi thiếu native memory từ **24/10/2026**, không phải crash mới được phát hiện hôm nay. Lần kiểm tra này giới hạn heap/metaspace và không tạo JVM crash mới.

Không sửa code nghiệp vụ, không phục hồi source từ ZIP, không đọc secrets, không khởi động production resources, không chạy migration hoặc thay đổi dữ liệu người dùng. Chỉ tạo các artifact kiểm tra trong target/ và báo cáo này.

## Kiểm tra nguyên nhân lệch phiên bản

Đọc lại Git hiện tại: HEAD e67b5d0 trên optimize-index-db; reflog có reset về HEAD và checkout từ develop sang optimize-index-db. Các file FollowRequestLookup/LegacyFollowRequestLookup và các test mới hiện là untracked. Tìm trong lịch sử Git reachable bằng `git log --all -S` không thấy implementation profileLabels, pendingFollowRequestIds hoặc callDurationSeconds trong các class tương ứng.

Các bằng chứng này xác nhận phần code tracked và phần file bổ sung hiện không đồng bộ. Cơ chế reset/checkout/chép snapshot có thể thay file tracked nhưng giữ lại untracked; không đủ bằng chứng để khẳng định thao tác hay người nào đã làm mất nội dung chưa commit. Không quy lỗi cho Redis.

Commit e67b5d0 đã cấu hình Jackson bỏ qua field JSON không biết để tránh crash khi đọc document, có nhắc callDurationSeconds. Điều này không tạo getter/field trên ChatMessage, nên test bảo toàn duration vẫn không biên dịch được. Không sửa test để bỏ yêu cầu bảo toàn dữ liệu.

Maven ban đầu dùng classes có sẵn trong target và skip compile main, nên chỉ hiện 23 lỗi testCompile. Compile riêng từ source thực đã xác nhận lỗi main tại adapter gọi method không tồn tại. Kết quả 454 tests PASS của ngày 25/10 không thể dùng làm kết quả cho source hiện tại.
