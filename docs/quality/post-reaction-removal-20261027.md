# Sửa hủy cảm xúc bài đăng — 27/10/2026

Trạng thái: Implemented / Partially Tested.

## Audit và thay đổi

- Confirmed Bug: giao diện app.js gửi POST /api/posts/{postId}/react với type null khi bấm lại nút cảm xúc hoặc chọn lại emoji hiện tại. ReactionRequest dùng @NotBlank nên HTTP trả 400 trước khi gọi LikeService.
- Already Correct: LikeService đã hiểu null là hủy, kiểm tra quyền đọc bài trước khi sửa, xóa theo postId/userId của principal, giảm bộ đếm và vô hiệu cache của viewer. Không sửa các luồng này.
- Bỏ @NotBlank trên ReactionRequest.type; giữ @Pattern cho sáu loại cảm xúc. null được phép để hủy; chuỗi rỗng, khoảng trắng và loại không hợp lệ vẫn trả 400. Giữ endpoint, response, authentication, rate limit và persistence hiện có.

## Kiểm chứng thực chạy

1. Thêm kiểm tra HTTP hủy cảm xúc với type null rồi chạy trước khi sửa: thất bại, mong đợi 200 nhưng nhận 400 với lỗi NotBlank.
2. Sau khi sửa:

```powershell
mvn --batch-mode '-Dtest=PostControllerWebTest,FeedQueryBudgetTest' test
```

BUILD SUCCESS: 22 kiểm tra, không lỗi. Bao gồm null hủy cảm xúc, bảy đầu vào hợp lệ (sáu loại và dạng viết thường), năm loại đầu vào không hợp lệ bị từ chối trước khi gọi service/rate limiter, cùng các kiểm tra query budget hiện có.

3. src/test/browser/post-reaction-removal.cjs dùng JavaScript/CSS thật và HTTP fixture riêng: 28 kịch bản đạt trên desktop 1280 và mobile 390. Bao gồm hủy sáu loại bằng nút hoặc cùng emoji, trạng thái sau tải lại trang, lỗi mạng phục hồi cảm xúc và thao tác nhanh hủy rồi thả lại hội tụ về trạng thái cuối. Kiểm tra POST và CSRF header.
4. JavaScript syntax và git diff --check đạt. Không có migration thay đổi.

Không ghi dữ liệu thật, không khởi động lại ứng dụng, không chạy production migration. Chưa kiểm tra mutation trực tiếp trên database người dùng. Cần khởi động lại backend đang chạy để nạp DTO mới.

## File thay đổi trong phạm vi này

- src/main/java/com/aloute/dto/request/ReactionRequest.java
- src/test/java/com/aloute/controller/PostControllerWebTest.java
- src/test/browser/post-reaction-removal.cjs
- Báo cáo này.

Không rollback/reset các sửa đang có. Nếu cần phục hồi riêng bản sửa, khôi phục annotation đã bỏ; không cần migration, nhưng hành vi hủy cảm xúc sẽ bị từ chối như trước.
