# Lưới hồ sơ không nhận bài đăng lại; link hồ sơ theo username — 28/10/2026

Trạng thái: Implemented / Tested trong phạm vi mô tả.

## Audit

- Confirmed Bug: `initPostPagination` gọi `appendGridTiles` cho cả loader bài chính chủ và loader Đăng lại. Fragment Đăng lại chứa bài gốc của người khác, nên ảnh đó bị thêm vào lưới hồ sơ mỗi khi mở tab Đăng lại. Đã tái hiện bằng JavaScript từ commit trước: lưới tăng từ 1 lên 2 ô sau một lần mở tab.
- Confirmed Bug: `/people/{username}` trước đây tìm tài khoản qua kết quả search bị giới hạn rồi chuyển hướng về `/profile/{id}`. Các link avatar/tên người dùng cũng chủ yếu dùng ID.
- Already Correct: counter hồ sơ truy vấn aggregate riêng, không lấy số ô trong lưới hoặc số bài trong trang. Quan hệ đăng lại thông thường không biến thành bài chính chủ. Không sửa counter hoặc quy tắc bài trích dẫn có lời bình của tác giả trong lượt này.

## Sửa mã nguồn

- `app.js`: chỉ cập nhật lưới khi phân trang trong `#profilePosts`; guard ngay trong `appendGridTiles` để không nhận bài từ tab khác hoặc wrapper repost. Tab Đăng lại vẫn tải và tương tác như cũ.
- `ViewController`: lọc media thuộc đúng ID chủ hồ sơ và không có `repostOf`, dùng chung cho trang đầu và fragment. Không thay query/cursor hoặc đọc thêm dataset.
- `UserService`: tra chính xác username không phân biệt hoa/thường, dùng chung mapper và `requireProfile` với đường ID; một user lookup, không tìm gần đúng, không lộ quyền qua URL.
- `/people/{username}` render hồ sơ trực tiếp; giữ `/profile/{id}`, `/profile/me`, `/profile/edit` tương thích. Username số hoặc `edit` không xung đột route ID/chỉnh sửa vì có prefix `/people/` riêng.
- Link avatar/tên trong feed/Đã lưu/tìm kiếm/gợi ý/yêu cầu theo dõi/danh sách chặn, link hồ sơ bản thân, thông tin chat và popup bạn bè dùng username khi dữ liệu có sẵn. Giữ fallback ID cho payload cũ hoặc tài khoản không còn nhãn username. JS encode đường dẫn, Thymeleaf dùng biến path; hỗ trợ điều hướng mềm cho `/people/`.
- Cập nhật asset versions; không đổi schema, dữ liệu, REST JSON, STOMP, query ranking hoặc phân quyền. HTML `/people/{username}` có chủ đích trả trang 200 thay vì chuyển hướng sang URL ID.

## Kiểm chứng

- Chạy lại 23 lớp Java liên quan: **156 tests, 0 failure/error/skipped**, BUILD SUCCESS.
- `ProfileUsernameRouteTest`: HTTP route username chữ/dấu chấm/số/`edit`, không redirect sang ID, anonymous về login; chọn media chính chủ ở trang đầu và fragment.
- `UserProfileQueryBudgetTest`: exact lookup không đọc user lần hai, cùng kiểm tra quyền; block và tài khoản không tồn tại vẫn bị từ chối.
- `FeatureTemplateTest`: render HTML thật, link username của feed/navigation/follow/blocked; sửa các expected URL vì hành vi link đổi theo yêu cầu. Giữ kiểm tra escaping và hành động.
- `profile-grid-workflow.cjs before`: tái hiện lỗi trên source commit trước. Bản sửa: 4/4 workflow desktop/mobile × sáng/tối đạt, mở tab Đăng lại không tăng số ô; tải thêm bài chính chủ vẫn thêm ô, quote không bị thêm, quay lại Đăng lại không tải trùng. Kiểm tra helper URL username số, dấu chấm, tên `edit`, encode và fallback ID.
- Chạy lại browser: 4 profile-grid, 4 blocked-accounts, 4 search, 6 saved-feed và 16 private-follow workflow đều đạt (34 tổng). Các expected URL trong fixture follow/blocked được đổi có chủ đích sang username; vẫn giữ test truy cập đường ID cũ.
- Browser dùng HTML Thymeleaf/CSS/JS thật và HTTP fixture riêng, không mở datasource người dùng.
- Syntax 4 file JavaScript và diff whitespace đạt. Chưa kiểm thử toàn bộ tích hợp chat/backend thật hoặc tải production; không báo cáo cải thiện latency/throughput.

## File và phục hồi

Java: `controller/ViewController.java`, `service/UserService.java`. JS: `app.js`, `features.js`, `chat.js`, `friends-panel.js`. Templates: `feed/index.html`, `features/index.html`, `follow-requests/index.html`, `fragments/navbar.html`, `fragments/bottom-nav.html`, `layout/base.html`, `chat/index.html`.

Tests: `FeatureTemplateTest`, `UserProfileQueryBudgetTest`, `ProfileUsernameRouteTest` (mới), `blocked-accounts-workflow.cjs`, `private-follow-workflow.cjs`, `profile-grid-workflow.cjs` (mới).

Khởi động lại backend, Ctrl+F5 để nạp Java/template/assets. Nếu cần phục hồi thì hoàn tác riêng commit sửa grid/username; không có migration/data rollback. Không gỡ hoặc sửa commit các chức năng trước đó.
