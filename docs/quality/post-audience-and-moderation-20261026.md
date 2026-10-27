# Biểu tượng quyền xem bài và khôi phục kiểm duyệt — 26/10/2026

## Phạm vi và bằng chứng

Đọc source hiện tại, giữ các thay đổi đang có. Đây là lát cắt sửa giao diện/quyền xem bài, không phải báo cáo hoàn thành toàn bộ refactor backend trong AGENTS.md.

| Phân loại | Kết quả xác minh | Xử lý |
|---|---|---|
| Confirmed Bug | Header bài chưa hiển thị PUBLIC/FRIENDS/ONLY_ME; RepostOrigin chưa mang visibility | Thêm biểu tượng SVG địa cầu, nhóm người, ổ khóa và metadata gốc ở cả mapper đơn/batch |
| Confirmed Bug | Chủ bài không được giải thích trạng thái kiểm duyệt; bài FLAGGED do mất kết nối không có thao tác phục hồi trực tiếp | Thông báo chỉ dành cho chủ bài; nút thử duyệt lại chỉ khi lỗi kết nối có thể phục hồi |
| Already Correct | Người theo dõi đã duyệt được đọc PUBLIC đã APPROVED trên tài khoản riêng tư | Giữ policy và kiểm thử cả scalar/batch, trang thường/cursor |
| Already Correct | FRIENDS cần theo dõi hai chiều; ONLY_ME dành cho tác giả; người chờ duyệt/bị gỡ theo dõi không được đọc nội dung tài khoản riêng tư | Không thay đổi các điều kiện này |
| Requires Integration Testing | Dịch vụ kiểm duyệt thật và bài đang lưu trên Render | Chưa phục hồi trên runtime; không sửa trạng thái bài trong database thật |

Kiểm tra PostgreSQL bằng kết nối **read-only**, không khởi tạo Spring application/Flyway. Với tác giả 26: một bài PUBLIC FLAGGED sau 5 lần `TEXT_SERVICE_UNAVAILABLE`; một FRIENDS cũng gặp lỗi đó; một FRIENDS APPROVED; một ONLY_ME APPROVED. Không đọc nội dung bài hoặc thông tin xác thực. Tại thời điểm kiểm tra có một người theo dõi, cũng là quan hệ hai chiều.

Probe cấu hình hiệu lực xác nhận kiểm duyệt bật, URL host localhost/cổng 8000, GET health không kết nối được (`ConnectException`). Đây là lỗi dịch vụ kiểm duyệt, không phải Redis hay điều kiện follow. Kết quả là snapshot khi kiểm tra, dữ liệu và dịch vụ có thể thay đổi sau đó.

## Implemented

- Posts sở hữu `PostModerationRetryService` và endpoint bổ sung `POST /api/posts/{postId}/moderation-retry`. Controller lấy actor từ principal; không nhận owner từ client.
- Chỉ tác giả được gửi lại bài gốc FLAGGED vì `TEXT_SERVICE_UNAVAILABLE` hoặc `MEDIA_SERVICE_UNAVAILABLE`. Không gửi lại quyết định nội dung, bài HIDDEN hay bài repost bằng thao tác này. PENDING là no-op để tránh reset job đang chạy; tắt kiểm duyệt thì từ chối thao tác.
- Rate limit 5 lần/giờ cho mỗi tác giả qua identity public port hiện có.
- Atomic compare-and-set ràng buộc id, tác giả, trạng thái/lý do kiểm duyệt, thời điểm xử lý/sửa và nội dung/media. Concurrent retry/admin decision không được ghi đè. Chỉ đưa về PENDING, reset bộ đếm/lease/lỗi tạm thời; không chuyển APPROVED, không đổi quyền xem hoặc quyền duyệt cộng đồng, không sửa nội dung hay lịch sử bài.
- Dispatch sau commit qua `PostReviewDispatch`; sử dụng coordinator/worker và PENDING recovery hiện có, không tạo queue mới. Worker vẫn phải duyệt thành công trước khi bài xuất hiện với người theo dõi.
- DTO bổ sung `moderationRetryAvailable` (chỉ true với chủ bài phù hợp); `RepostOrigin.visibility`. Không thay URL/JSON fields cũ, STOMP, schema database, SQL migration hoặc ranking.
- Fragment `post-metadata.html` hiển thị biểu tượng với title/aria-label trên feed/profile/repost/quote/saved; search JS có cùng SVG/nhãn. Thông báo kiểm duyệt và nút retry chỉ hiện cho chủ bài. Không đưa mã lỗi kỹ thuật/secret vào giao diện.
- Handler khóa các nút cùng bài trong khi gửi, gửi CSRF qua API helper hiện có; khi lỗi cho phép thử lại, thành công tải lại trang.
- Tăng phiên bản tài nguyên CSS/JS để trình duyệt lấy giao diện mới.

## Files

Mới:

- `src/main/java/com/aloute/posts/domain/PostReviewRetryPolicy.java`
- `src/main/java/com/aloute/posts/application/PostModerationRetryService.java`
- `src/main/java/com/aloute/posts/web/PostModerationRetryController.java`
- `src/main/resources/templates/fragments/post-metadata.html`
- Tests: `PostMetadataTemplateTest`, `PostAudienceMappingTest`, `PostModerationRetryServiceTest`, `PostModerationRetryControllerTest`, `src/test/browser/post-audience-workflow.cjs`.

Sửa:

- `PostResponse.java`, `PostResponseMapper.java`: metadata và ánh xạ không thêm truy vấn cho mỗi thẻ.
- Templates `feed/index.html`, `profile/view.html`, `features/index.html`, `layout/base.html`.
- `static/css/main.css`, `static/js/app.js`, `static/js/features.js`.
- Tests `PrivateProfileContentTest`, `PostUseCaseWiringTest`, `ModuleBoundaryTest`.

Không xóa file hoặc thay migration. Các thay đổi khác đã có trong workspace được giữ lại.

## Kiểm chứng

- Maven offline `package`: **BUILD SUCCESS, 462 tests: 461 đạt, 1 bỏ qua** (application context cần DB_URL), không failure/error. Log: `target/post-audience-full-tests-20261026.log`. DB_URL được bỏ khỏi môi trường test; không khởi chạy ứng dụng trên database thật. Không cộng lại kết quả của các lần chạy trước.
- Test quyền xem duyệt lại dùng policy/mapper/retry service/worker thực, thay persistence và dịch vụ ngoài bằng doubles. Kiểm tra FLAGGED → PENDING → APPROVED; chỉ sau APPROVED người theo dõi đọc được; gỡ theo dõi thu hồi cả hai đường đọc.
- CAS concurrency test chạy hai tác vụ đồng thời với storage double; kiểm tra predicates bảo vệ admin/edit race. Chưa chạy concurrency test này trên PostgreSQL thật: **Partially Tested**.
- Architecture và wiring tests: controller qua application, application dùng public collaborator ports và owned persistence.
- Playwright/Edge fixture với tài nguyên ứng dụng thật: 3 nhóm kịch bản mới (desktop/mobile, retry), 13 kịch bản private/follow cũ đạt. Không thao tác dữ liệu người dùng. Fixture ban đầu trả nhầm HTML cho JS phụ, đã sửa route fixture và chạy lại thành công.
- JavaScript syntax checks app.js/features.js/browser script đạt. Đã xem ảnh kiểm tra giao diện mobile.
- Live upstream moderation và hành vi trên app đang chạy: **Blocked** bởi dịch vụ localhost:8000 chưa lắng nghe; không tuyên bố bài đã được xuất bản.

## Chạy và phục hồi

Chạy lại backend trong IDE để nạp class/controller mới; tải lại trình duyệt. Bật dịch vụ kiểm duyệt hiện có ở cổng 8000 bằng cách chạy hiện tại của dự án. Probe Docker xác nhận engine không sẵn sàng, nên chưa thể chạy container trong phiên này. Sau khi mở Docker Desktop và engine hoạt động, từ thư mục gốc repository chạy:

```powershell
docker compose --env-file .env -f moderation/docker-compose.standalone.yml up -d --build
```

Sau khi dịch vụ hoạt động, chủ bài bấm **Thử duyệt lại** và tải lại trang sau khi worker xử lý. Nếu dịch vụ tiếp tục lỗi, giữ bài không hiển thị cho người khác; không tắt policy để làm bài hiện ra.

Không tự restart backend ở đây vì startup có thể chạy Flyway trên database Render dùng chung. Không ghi/sửa bài thật, không chạy migration, không tắt validation hoặc bật chế độ bỏ kiểm duyệt.

Rollback bằng bản source/artifact trước lát cắt này, không cần migration ngược. Việc gỡ endpoint/UI không được tự chuyển trạng thái bài; PENDING còn lại vẫn thuộc coordinator/worker hiện có. Giữ các thay đổi private và migration đã có từ trước.

## Kiểm tra lại sau phản hồi ảnh mới — 14:52 ngày 26/10/2026

Ảnh mới đã có các biểu tượng và dòng chờ duyệt, chứng tỏ giao diện mới đã được nạp. Viewer đã đọc được một bài FRIENDS; hai bài còn lại trong ảnh chủ bài mang trạng thái PENDING.

Đọc lại database bằng kết nối read-only lúc 14:49 và 14:52 cho kết quả mới: cả bốn bài của tác giả 26 đều APPROVED, gồm hai FRIENDS, một ONLY_ME, một PUBLIC. Không có thao tác ghi hoặc tự duyệt bài trong phiên chẩn đoán này. Không khẳng định ai/worker nào đã hoàn tất quyết định giữa thời điểm chụp ảnh và thời điểm kiểm tra.

Query chẩn đoán tổng hợp đối chiếu trạng thái duyệt, quyền bài, quan hệ hai chiều và block hiện tại: follower được đọc hai FRIENDS và một PUBLIC; ONLY_ME vẫn bị loại. Artifact: `target/current-profile-visibility-audit.json`. Đây là kiểm tra dữ liệu/policy bằng SQL read-only, không phải kiểm thử HTTP bằng phiên đăng nhập của người dùng.

Trang đã mở trước khi worker hoàn thành vẫn giữ HTML PENDING cho đến khi tải lại; source hiện tại không tự cập nhật các thẻ bài sau quyết định kiểm duyệt. Đã yêu cầu người dùng Ctrl+F5 ở cả trang chủ bài và trang người theo dõi để xác nhận trạng thái mới. Chưa có xác nhận hiển thị trên phiên trình duyệt thật tại thời điểm ghi chú.

Local localhost:8000 vẫn không có listener. Đã thử khởi động Docker Desktop bằng CLI; lệnh nhận yêu cầu nhưng Docker engine vẫn chưa sẵn sàng. Không thay cấu hình Docker, không restart WSL/Redis, không restart backend, không đổi database hay bỏ kiểm duyệt. Việc các bài đã APPROVED không chứng minh dịch vụ kiểm duyệt local đã phục hồi.
