# Kiểm tra trước PR vào main — 28/10/2026

Người dùng yêu cầu đẩy code và tạo pull request vào `main`; không yêu cầu merge hoặc deploy.

- Cấu hình đưa lên Git: `server.port: ${SERVER_PORT:8080}`. Cấu hình cổng 8082 đang dùng trên máy được giữ ngoài commit. Profile/helper LAN và hướng dẫn mới dùng mặc định 8080; không thay `.env` thật.
- PR bao gồm các commit sửa social/privacy/UI/search/saved/profile trước đó và phần ghép ZIP có chọn lọc. So với main, lịch sử nhánh còn bao gồm commit login/UI/gỡ 2FA đã có trước lượt ghép; không xem đó là thay đổi mới của bước chuẩn bị PR.
- Quét Gitleaks v8.30.1 trên phần staged và ba commit trước ghép: không phát hiện secret. Công cụ tải từ release chính thức và kiểm tra SHA256. Không đưa `.env`, Firebase credentials, private certificates, log hoặc build artifacts vào commit.
- Kiểm tra cuối: 43/43 test đạt trong DistributedCallInfrastructureCheckTest, FeatureTemplateTest, PostEditContractTest, PostEditingServiceTest, CallSignalingContractTest, UploadControllerTest, MediaUploadModerationServiceTest. Log local được Git bỏ qua: `target/pr-targeted-tests.log`.
- PowerShell AST, Bash/JavaScript syntax và `git diff --cached --check` đạt. Kết quả đầy đủ trước đó: 24 integration tests PostgreSQL/Redis, 46 browser workflows, 22 Python tests; package thành công khi bỏ qua chạy test ở bước đóng gói.
- Full Java suite chưa xanh: 524 test, 516 đạt, 6 failure, 1 error, 1 skipped. Đã tái hiện đúng bảy ca không đạt trên nền sạch c571787; không sửa/suppress test để che lỗi. Chi tiết: [báo cáo ghép ZIP](zip-merge-20261028.md).
- Migration mới V20261112 chỉ thêm index phục hồi call; không sửa migration cũ, xóa/backfill dữ liệu hay chạy migration trên database ứng dụng đang dùng.
- Hai thiết bị thật, TURN qua NAT, provider moderation thật và tải nhiều instance chưa được xác minh. Không merge/deploy production hoặc thay hạ tầng bên ngoài.

Trạng thái: phần ghép đã triển khai và kiểm tra như trên; chất lượng toàn dự án **PARTIAL** do các lỗi baseline và kiểm tra tích hợp bên ngoài còn thiếu. Trạng thái CI trên GitHub phải được đọc riêng sau khi tạo PR.
