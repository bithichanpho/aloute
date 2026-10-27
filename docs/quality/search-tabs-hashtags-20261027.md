# Sửa tab Bài viết và tìm hashtag — 27/10/2026

Trạng thái: Implemented / Tested trong phạm vi bên dưới. Không phải báo cáo hoàn thành toàn bộ refactor backend.

## Audit và nguyên nhân xác minh từ source

| Phân loại | Bằng chứng | Xử lý |
|---|---|---|
| Confirmed Bug | app.js chỉ chạy tìm kiếm khi đổi tab nếu keyword khác rỗng | Tab Bài viết rỗng dùng API cursor feed hiện có |
| Confirmed Bug | main.css đặt display:flex cho search-discover/search-tabs/search-clear, ghi đè hidden mặc định | Scoped .search-page [hidden] để ẩn đúng; tabs luôn sẵn có |
| Confirmed Bug | DemoDataSeeder/DemoFeedExpansionSeeder ghi nội dung có hashtag nhưng không setHashtags; search cũ chỉ so mảng hashtags | Đọc tương thích bài thiếu tag, bổ sung tag cho bài demo tạo mới |
| Already Correct | SearchController truyền nguyên hashtag đã trim, app.js encodeURIComponent; PostResponseMapper kiểm tra quyền viewer theo batch | Giữ contract và đường kiểm tra quyền |
| Requires Integration Testing | HTTP với phiên đăng nhập và dữ liệu người dùng thực | Chưa thực hiện, không truy cập DB Render để thử |

## Thay đổi và ownership

- Posts sở hữu PostHashtagQuery, dùng chung bởi PostService.searchPosts và PostFeedReader. Giữ nhánh tag đã materialize; fallback kết hợp full-text trên nội dung với regex hashtag nguyên vẹn, không phân biệt hoa/thường. Chỉ nhận bảng chữ cái/số/gạch dưới và độ dài giống parser, không diễn giải regex do người dùng nhập. Thứ tự/limit/offset và phân quyền mapping giữ nguyên. Không load toàn dataset vào RAM hoặc thêm query theo từng bài.
- Bổ sung setHashtags ở các đường tạo bài demo mới; bài đã tồn tại được tìm qua compatibility read, không sửa/backfill dữ liệu cũ.
- Search UI dùng /api/posts/feed/cursor?tab=foryou khi ô trống ở tab Bài viết; phân trang bằng nextCursor, không giả lập offset. Mọi người vẫn hiện gợi ý/xu hướng khi ô trống. Kết quả tìm kiếm ẩn phần gợi ý đúng.
- Đổi tab lấy nội dung ô hiện tại; clear/Escape/trend click hủy debounce cũ. Giữ AbortController, chống response cũ và tái kiểm tra quyền khi tìm lại.
- Không đổi REST response/schema/STOMP, DB schema, migration, ranking, cache keys hoặc quyền xem. Cập nhật phiên bản main.css/app.js để trình duyệt nạp asset mới.

File sản phẩm: static/js/app.js, static/css/main.css, templates/search/index.html, templates/layout/base.html, service/PostService.java, posts/infrastructure/PostHashtagQuery.java (mới), posts/infrastructure/PostFeedReader.java, config/DemoDataSeeder.java, config/DemoFeedExpansionSeeder.java.

File kiểm tra: FeatureTemplateTest.java, PaginationControllerTest.java, PrivateProfileContentTest.java; mới PostHashtagQueryTest.java, PostHashtagSearchIT.java, browser/search-workflow.cjs.

## Kiểm chứng thực chạy

Baseline trước sửa: 15/15 Java đạt (PaginationControllerTest, FeedQueryBudgetTest, PrivateProfileContentTest); performance-search.cjs đạt kiểm tra abort, stale response, cache, revalidation và video preload.

Sau sửa:

```powershell
mvn --batch-mode '-Dtest=FeatureTemplateTest,PaginationControllerTest,PostHashtagQueryTest,PostHashtagSearchIT,PrivateProfileContentTest,FeedQueryBudgetTest,FeedQueryServiceTest,FeedCachedVisibilityTest,ModuleBoundaryTest#crossModuleDependenciesUsePublicApis+feedOnlyUsesPublicPostReadContracts+migratedModulesHaveNoCycles' test
```

52 tests, 0 failure/error/skipped, BUILD SUCCESS. PostHashtagSearchIT chạy PostgreSQL 18.6 thật: cluster local riêng tạo trong thư mục tạm, database tên ngẫu nhiên do DisposablePostgres sở hữu. Áp dụng 29 migration hiện có chỉ trong DB thử, kiểm tra tag materialized/legacy, hoa/thường, tag dài hơn, chữ thường không có #, hashtag dính vào chữ trước, tiếng Việt/gạch dưới và phân trang SQL. Database thử được test tự dọn; cluster thử được dừng sau kiểm chứng. Không khởi động ứng dụng với datasource người dùng.

Chỉ chạy 3 architecture gates liên quan; không tuyên bố toàn bộ ModuleBoundaryTest/suite đã đạt. Hai lỗi notification ModerationStatus đã ghi nhận từ các lượt trước nằm ngoài phạm vi sửa này, không suppress.

search-workflow.cjs: 4/4 workflow đạt (1280/390px × sáng/tối), HTML Thymeleaf và CSS/JS thật trên HTTP fixture. Kiểm tra tab rỗng, ẩn gợi ý, cursor opaque/phân trang, tìm có/không #, mã hóa %23, đổi tab, clear/debounce, click xu hướng, empty state, lỗi tải và thử lại. Đã xem ảnh desktop tối/mobile sáng. performance-search.cjs chạy lại đạt. JavaScript syntax và git diff --check đạt.

Không đo latency/p95/query plan trên dataset lớn: performance NOT VERIFIED. Compatibility fallback dùng full-text predicate hiện có nhưng chưa tuyên bố planner chọn index hay nhanh hơn. Chưa kiểm thử đa instance hay thao tác trên dữ liệu người dùng thực.

## Nạp thay đổi và phục hồi

Khởi động lại backend để nạp Java/template mới, sau đó Ctrl+F5 trang Tìm kiếm. Không cần migration mới hoặc bật lại seeder. Nếu cần phục hồi, hoàn tác riêng những phần tìm kiếm/helper/seed tag mới trong các file kể trên; giữ các thay đổi khác đang có trong workspace. Không có dữ liệu cần rollback. Không push/reset/deploy hoặc sửa cơ sở dữ liệu người dùng.
