# Yêu cầu theo dõi: tên và liên kết hồ sơ — 25/10/2026

Phạm vi: sửa mục yêu cầu theo dõi ở `/settings#settings-requests`, theo yêu cầu trực tiếp của người dùng. Không triển khai lại toàn bộ kế hoạch refactor backend trong AGENTS.md.

## Kết quả audit

| Phân loại | Bằng chứng | Xử lý |
|---|---|---|
| Confirmed Bug | Template và `appendFollowRequest` ghép `Tài khoản #` với `followerId`; response chỉ có dữ liệu request | Thêm public profile labels và hiển thị tên + @username |
| Confirmed Bug | Nút duyệt cũng có `data-request-id`; `target.closest('[data-request-id]')` chọn chính nút đó | Chọn `.feature-list__row[data-request-id]` để xóa cả dòng sau API thành công |
| Already Correct | Liên kết hồ sơ đã dùng `/profile/{followerId}` | Giữ URL, đổi nội dung liên kết |
| Already Correct | UserDirectory là public API; cursor query giới hạn `size + 1` | Dùng API Users, chỉ bổ sung labels cho các dòng của trang hiện tại |
| Requires Integration Testing | Chưa chạy luồng với DB và tài khoản đăng nhập thật | Không tuyên bố live integration PASS |

Stack trong build: Java target 21, Spring Boot 4.1.1; JDK thực thi 22, Maven 3.9.11. Git ban đầu chưa có commit, toàn bộ source là untracked. Đã tạo nhánh `codex/follow-request-profile`, không stage/commit/push/reset dữ liệu hiện có.

## Thay đổi và ownership

| File | Thay đổi |
|---|---|
| `src/main/java/com/aloute/users/api/UserDirectory.java` | Public immutable `ProfileLabel` và bulk `profileLabels` |
| `src/main/java/com/aloute/users/infrastructure/JpaUserDirectory.java` | Users sở hữu đọc projection; dedupe IDs, loại null, batch tối đa 500 |
| `src/main/java/com/aloute/repository/IUserRepository.java` | Projection chỉ gồm ID, username, fullName; không tải credentials |
| `src/main/java/com/aloute/dto/response/FollowRequestResponse.java` (mới) | Giữ id/followerId/targetId/createdAt, thêm followerUsername/followerFullName |
| `src/main/java/com/aloute/service/SocialFeatureService.java` | Bổ sung labels qua public Users API cho cả list và cursor; không đổi document lưu trữ |
| `src/main/java/com/aloute/service/NotificationService.java` | Hoàn thiện adapter constructor tương thích với method mới của UserDirectory |
| `src/main/resources/templates/features/index.html` | Render tên và @username cùng link hồ sơ; escape nội dung bằng Thymeleaf |
| `src/main/resources/static/js/features.js` | Đồng bộ render khi Xem thêm; dùng textContent; sửa xóa dòng sau duyệt |
| `src/main/resources/static/css/features.css` | Hai dòng tên/username trong link |
| `src/main/resources/templates/layout/base.html` | Cập nhật phiên bản CSS/JS để trình duyệt lấy asset mới |

Tests: bổ sung `FollowRequestControllerWebTest.java`, `follow-request-profile.cjs`; mở rộng `SocialFeatureServiceTest.java`, `FeatureTemplateTest.java`, `UserDirectoryTest.java`.

Không đổi endpoint, HTTP method, pagination, ID, timestamp, document keys, schema hoặc migration. Backend vẫn lấy người nhận từ principal và kiểm tra quyền quyết định như trước. Request list chỉ bổ sung hai trường public, không email/password/token. Không thay đổi block/mute/private rules, post/feed/chat/call/media. Không xóa dữ liệu thực hoặc restart dịch vụ đang chạy.

## Kiểm chứng

- Baseline trước sửa: `mvn -q -Dtest=SocialFeatureServiceTest,FeatureTemplateTest,UserDirectoryTest test`: **19 tests, 0 failures/errors/skips**.
- Sau sửa: `mvn -q -Dtest=SocialFeatureServiceTest,FeatureTemplateTest,UserDirectoryTest,FollowRequestControllerWebTest,ModuleBoundaryTest test`: **39 tests, 0 failures/errors/skips**. Bao gồm compile, 12 architecture tests, JSON contract, principal spoofing/anonymous rejection, batch projection, cursor lookahead, thiếu tên/tài khoản và HTML escaping.
- Kiểm tra consumer của public API Users: `mvn -q -Dtest=NotificationServiceDeliveryTest,NotificationApplicationServiceTest,NotificationWiringTest test`: **14 tests, 0 failures/errors/skips**. Tổng sau sửa: **53 Java tests PASS**; warning outbox là tình huống lỗi được giả lập trong test.
- Browser: `node src/test/browser/follow-request-profile.cjs`, Edge headless, Playwright module từ runtime có sẵn. **PASS tại 1280px và 390px**: tên + @username, điều hướng `/profile/6`, Xem thêm/duplicate page, missing labels, XSS-safe text, accept/reject và xóa cả dòng. Dùng fixture API và source JS/CSS thật; không phải kiểm thử DB/live server.
- Browser regression ban đầu thất bại tại bước xóa dòng sau accept, xác minh lỗi chọn chính nút. Sau khi sửa selector, cùng test qua cả hai kích thước.
- Không đo hiệu năng/EXPLAIN/load test; chỉ xác minh số lần bulk lookup bằng test doubles. Không tuyên bố cải thiện latency.

## Áp dụng và rollback

Khởi động lại backend theo quy trình phát triển hiện có rồi tải lại trang cài đặt. Không cần migration hoặc sửa dữ liệu.

Rollback từng thay đổi trong bảng trên về source trước lát cắt này, build và khởi động lại backend. Do repository chưa có commit, không dùng `git reset`/`git clean` để rollback; các lệnh đó có thể ảnh hưởng toàn bộ source hiện có.

Trạng thái: **Implemented, Partially Tested** — unit/architecture/REST/template và browser với fixtures đã chạy; tích hợp live DB/session chưa chạy.
