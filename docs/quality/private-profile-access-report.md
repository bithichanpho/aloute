# Quyền xem hồ sơ riêng tư — 25/10/2026

## Yêu cầu và audit

Chỉ chủ tài khoản và người **hiện đang được chấp nhận theo dõi** mới được xem bài viết, ảnh và tab đăng lại của tài khoản riêng tư. Pending request và việc từng theo dõi không cấp quyền. Khi quan hệ follow đã bị xóa (bỏ theo dõi hoặc bị xóa khỏi followers), các lần đọc tiếp theo phải từ chối nội dung.

| Phân loại | Bằng chứng hiện tại | Xử lý |
|---|---|---|
| Confirmed Bug | `getRepostsByUser` và `getRepostsByUserCursor` chỉ authorize tác giả bài gốc, không authorize người sở hữu danh sách đăng lại | Kiểm tra quyền đọc nội dung hồ sơ trước khi đọc các repost |
| Confirmed Bug | Template luôn có tabs/list/grid/repost loader cho hồ sơ riêng tư chưa được follow | Render thông báo riêng tư; không render nội dung hoặc loaders khi chưa có quyền |
| Confirmed Bug | Follow button chỉ đổi nhãn; bỏ theo dõi trên trang riêng tư giữ DOM nội dung đã tải | Tải lại hồ sơ riêng tư sau follow mutation thành công để backend xác định quyền hiện tại |
| Already Correct | `ContentAccessService.canRead` và `AccessBatch.canRead` chặn bài viết của private author khi thiếu follow edge | Giữ nguyên rule và bổ sung gate cho toàn bộ truy vấn nội dung hồ sơ |
| Already Correct | Feed cache hit reload cards và kiểm tra quyền bằng Posts | Thêm regression thu hồi follow khi cache chưa bị evict |
| Requires Integration Testing | Chưa có phép chạy với DB/session/browser tài khoản thật trong tác vụ này | Test dùng persistence doubles, không tuyên bố kiểm chứng live |

Build dùng Java target 21 / Spring Boot 4.1.1; môi trường chạy JDK 22 / Maven 3.9.11. Git đang ở `codex/follow-request-profile`, chưa có commit, source có sẵn đều untracked; giữ nguyên thay đổi của tác vụ trước. Không commit/push/deploy/restart dịch vụ hoặc đọc secrets.

## Implemented

| File | Ownership / thay đổi |
|---|---|
| `src/main/java/com/aloute/service/ContentAccessService.java` | `profileContentVisible`: active + không block + owner/public/current approved follow. Hồ sơ cơ bản vẫn hiển thị để gửi yêu cầu follow |
| `src/main/java/com/aloute/service/PostService.java` | Gate cả timeline và repost, cả offset và cursor. Không có quyền trả trang rỗng, cursor rỗng, hasMore=false, không đọc nội dung |
| `src/main/resources/templates/profile/view.html` | Không render list/grid/repost/tabs/loaders cho private outsider/pending; thông báo chờ duyệt |
| `src/main/resources/static/js/app.js` | Follow mutation trên hồ sơ riêng tư tải lại trạng thái từ backend, không giữ nội dung của lượt xem trước |
| `src/main/resources/templates/layout/base.html` | Tăng phiên bản asset app.js để browser tải bản mới |
| `src/test/java/com/aloute/service/PrivateProfileContentTest.java` (mới) | Real access policy + real mapper với persistence doubles; REST/fragment contract và các trạng thái quyền |
| `src/test/java/com/aloute/PrivateProfileTemplateTest.java` (mới) | Private notice, HTML không chứa nội dung/ảnh/loaders kể cả model có dữ liệu; owner/follower/public vẫn có tabs và bài |
| `src/test/java/com/aloute/service/ContentAccessServiceTest.java` | Phân biệt quyền xem hồ sơ cơ bản với quyền đọc nội dung |
| `src/test/java/com/aloute/service/FeedQueryBudgetTest.java` | Fixture owner cấp quyền profile gate mới; vẫn kiểm tra một access batch, không assemble lại quyền theo từng bài |
| `src/test/java/com/aloute/feed/FeedCachedVisibilityTest.java` | Follow bị thu hồi khiến private cards biến mất trên cache hit dù eviction chưa chạy |
| `src/test/browser/private-profile-follow.cjs` (mới) | Chạy app.js thật với fixture API: private-unfollow, private-pending, public-follow |

Chỉ dựa vào follow edge hiện tại trong repository để cấp quyền; không dùng lịch sử follow, pending request, cache relationship IDs hay viewerId do client khai. Backend giữ per-post checks sau profile gate: được xem hồ sơ không có nghĩa được xem bài gốc private của người khác, ONLY_ME, blocked/group/moderated content.

Không thay endpoint/HTTP method/response shape/STOMP/schema/database; không xóa quan hệ follow hoặc dữ liệu. Tác vụ không thêm endpoint/UI xóa follower mới: test thu hồi quyền mô phỏng quan hệ đã bị xóa khỏi persistence. Không thay ranking, thuật toán feed hoặc định dạng cursor.

## Kiểm thử

Baseline: `mvn -q -Dtest=ContentAccessServiceTest,ContentAccessBatchTest,FeedQueryBudgetTest,FeedCachedVisibilityTest,UserProfileQueryBudgetTest test` — **36 tests PASS**, không failures/errors/skips.

Tái hiện trước sửa: `mvn -q -Dtest=PrivateProfileContentTest test` — **4 tests, 2 failures**. Outsider và anonymous vẫn đọc được public originals trong danh sách repost của private owner; chính tests này qua sau sửa.

Sau sửa: `mvn -q -Dtest=PrivateProfileContentTest,PrivateProfileTemplateTest,ContentAccessServiceTest,ContentAccessBatchTest,FeedQueryBudgetTest,FeedCachedVisibilityTest,UserProfileQueryBudgetTest,PostControllerWebTest,ModuleBoundaryTest test` — **61 tests PASS**, không failures/errors/skips; gồm **12 architecture tests**.

Bổ sung kiểm tra API spoofing và gọi HTML fragment trực tiếp, chạy lại `mvn -q -Dtest=PrivateProfileContentTest test` — **10 tests PASS**, không failures/errors/skips. Tổng các test cases khác nhau sau sửa: **63 Java tests PASS** (61 ở lượt trước + 2 contract cases mới).

`node src/test/browser/private-profile-follow.cjs` — **3 scenarios PASS**, không pageerror. Edge headless và Playwright có sẵn; fixture API, không kết nối backend/DB thật. Kiểm tra private-unfollow xóa DOM bài/repost sau reload; private-pending vẫn khóa; public-follow giữ tương tác hiện tại. `node --check src/main/resources/static/js/app.js` PASS.

Không chạy DB integration, thao tác kick bằng tài khoản thật, load test hoặc concurrency với giao dịch PostgreSQL thật. Không hứa xóa nội dung đã tải khỏi các tab/máy khác hoặc thay đổi quyền có hiệu lực nguyên tử giữa mọi truy vấn đang chạy. Backend kiểm tra quyền hiện tại ở mỗi lần đọc mới; client chủ động unfollow trên hồ sơ riêng tư sẽ reload.

## Áp dụng / rollback

Khởi động lại backend theo quy trình phát triển hiện tại, tải lại hồ sơ. Không cần migration/backfill hoặc sửa dữ liệu. Rollback các thay đổi trong bảng về source trước tác vụ này, build và restart; giữ mọi thay đổi có sẵn của tác vụ trước. Không dùng git reset/clean vì source chưa được commit.

Trạng thái: **Implemented, Partially Tested** — unit/architecture/REST/fragment/template/browser fixture được kiểm chứng; live DB/session và concurrency chưa kiểm chứng. Không kết luận có lỗi riêng tư ở feed bài gốc khi baseline source đã chặn đúng; lỗi tái hiện chắc chắn nằm ở quyền của danh sách đăng lại và DOM sau đổi follow.
