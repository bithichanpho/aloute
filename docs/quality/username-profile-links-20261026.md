# Đường dẫn hồ sơ theo username — 26/10/2026

## Kết quả và phạm vi

Implemented: hồ sơ dùng `/@username`; ví dụ `/@casotoichoi`. `/profile/{id}`, `/profile/me` và `/people/{username}` vẫn tồn tại, chuyển hướng tạm thời sang link mới sau khi kiểm tra quyền. `/profile/edit` giữ nguyên. Username dạng số không bị hiểu thành ID. Tra cứu theo username chính xác, không phân biệt hoa/thường; không dùng kết quả tìm kiếm gần đúng.

Already Correct: username, ràng buộc duy nhất và lookup ignore-case đã có trong repository. Không sửa entity, ID, repository schema, JSON DTO, REST commands, STOMP hay dữ liệu. Không thêm/sửa migration. ID vẫn là khóa cho bài viết/follow/chat và mọi API JSON hiện hành.

## Ownership và bảo vệ

- UserService sở hữu tra cứu username và dùng chung `profileOf` với lookup ID. Một user read + aggregate relationship query; không đọc user lần hai hoặc lookup riêng cho từng link trong danh sách.
- `users.api.ProfileLinks` xây URL nội bộ và encode username; JS dùng `Aloute.profileUrl` cùng quy tắc. Hỗ trợ username cũ dạng email. Handle thiếu/không an toàn dùng link ID cũ, thiếu cả ID trả `#`; không tạo URL ngoài ứng dụng hoặc dùng ID cho lookup username dạng số.
- Username route yêu cầu đăng nhập như đường ID trước đây. Private/block/deactivated vẫn đi qua ContentAccessService; bị chặn hoặc không còn hoạt động trả 404 như policy cũ, không đọc nội dung trước khi kiểm tra quyền. Private outsiders chỉ có metadata được phép, nội dung vẫn lọc theo viewer ID.
- Thêm no-store cho `/@*` và alias `/people/**`; không dùng permanent redirects. Link ID kiểm tra quyền trước khi chuyển hướng. Giữ các quy tắc private/follow-request/close-friend/moderation hiện hành.
- Mọi template xây path trong `th:with`, rồi dùng biến trong `th:href`, tuân thủ restricted URL expression của Thymeleaf. Không nới cấu hình thực thi biểu thức.

## Những nơi đã cập nhật

- Backend: ViewController; UserService; SecurityConfig; NoCacheViewConfig. Mới: users/api/ProfileLinks.java.
- Template: feed/index.html, activity/index.html, features/index.html, fragments/navbar.html, fragments/bottom-nav.html. layout/base.html và chat/index.html đổi phiên bản script để nạp bản mới.
- JS: app.js (feed/search/people/reposter/mentions/shared helper và soft navigation cho menu hồ sơ), features.js (request/block/mentions), chat.js (link ở chi tiết chat), friends-panel.js (link trong chat nổi). Các hành động trên bài và quan hệ vẫn gửi ID.
- Tests mới: UsernameProfileTest, UsernameProfileControllerTest, ProfileLinkTemplateTest, UsernameProfileIT, browser/username-profile-workflow.cjs. Tests cũ FeatureTemplateTest/ProfilePostCountTest và hai browser fixtures được cập nhật expectation URL đúng theo yêu cầu; vẫn kiểm tra tương thích ID ở controller và các fixture legacy.
- Không xóa file hoặc reset những thay đổi đã có trong cùng workspace. Ngoài phạm vi: đổi username, lịch sử alias username hoặc refactor kiến trúc các module khác.

## Kiểm chứng thực tế

- Tested: `mvn -o -B -Dit.test=UsernameProfileIT verify` với heap giới hạn và timezone UTC: BUILD SUCCESS. Full suite 501 tests, 0 failures/errors, 1 skipped; PostgreSQL integration 1 test, 0 failures/errors/skipped. Log `target/username-profile-tests-20261026.log`.
- PostgreSQL 18.6 cục bộ, database ngẫu nhiên riêng: username hoa/thường/dấu chấm/số/email tra đúng ID có sẵn; ID lookup giữ nguyên; không mất user; private follow/removed follow/block/deactivation vẫn có hiệu lực. Không dùng DB app thật. Database thử đã thu hồi và server PostgreSQL thử đã dừng.
- Tested: SSR template cho author/quote/reposter, request, notification, block, menu của mình; legacy email encode đúng một lần. Bộ đếm bài vẫn là tổng riêng với trang nội dung. Controller test giữ login, chuyển hướng link cũ, phân biệt username số và ID, không tin viewer do client gửi, 404 không đọc bài.
- Browser mới 2 cấu hình 1280/390px đạt: feed/quote/reposter, safety helper, profile link ở chat nổi, search users/posts, request cursor, URL trực tiếp, menu soft navigation và Back. Dùng HTML Thymeleaf thật và JS/CSS thật với API giả lập; chuyển hướng HTTP của link ID kiểm chứng ở MVC, không tính redirect fixture giả là browser test.
- Hồi quy browser: private-follow 13, blocked-accounts 4, remove-follower 4, activity-read 5, activity-navigation 3, post-audience 3 — tất cả đạt. JS syntax cho 4 file và scoped git diff --check đạt.
- Partially Tested: chưa xác nhận bằng tài khoản đăng nhập trên app đang chạy; không load test hoặc đo cải thiện hiệu năng. Không tự khởi động backend kết nối database dùng chung.

## Áp dụng và rollback

Chạy lại app để nạp route/service mới, rồi Ctrl+F5. Không chạy migration mới. Nếu phục hồi, hoàn tác đồng bộ phần route/helper/link templates và JS của tác vụ này; không reset toàn file có sửa đổi trước. Dữ liệu và khóa ID không đổi nên không cần data rollback.
