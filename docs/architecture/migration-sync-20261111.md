# Đồng bộ migration V20261111 giữa hai máy

## Phạm vi

Repo đã bổ sung đúng file `V20261111__notification_outbox_in_app_delivery.sql` từ `D:/LTWEB/src.zip`, giữ nguyên SQL và tên/version. Không đổi 28 migration cũ, không sửa Flyway history, không tắt validation hoặc clean protection. Sau bước đồng bộ schema, luồng notification và follow được hợp nhất theo báo cáo `../quality/zip-merge-20261027.md`; giữ 2FA và các chức năng hiện tại.

Migration thêm `public.notification_outbox.in_app_delivered_at` nullable và index tương ứng. Bản hợp nhất đã ánh xạ cột này trong entity/worker và checkpoint sau khi phát WebSocket, trước khi thử gửi Firebase. Callback tạo outbox sau commit dùng transaction mới; thông báo chấp nhận follow được tạo trong transaction của follow edge. Kiểm thử JPA và transaction dùng PostgreSQL thử riêng. Điều này không bảo đảm hai luồng worker khác source tương thích khi chạy cùng lúc; cần đồng bộ bản hợp nhất trên các máy dùng chung database.

SHA-256 của SQL chuẩn hóa LF: `3100d6b14cc402cc3c2b2a7b8e3574102e6bb58b5cad9e7b5dafb3ca156342d8`. Contract test khóa checksum này cùng baseline cũ. Không sửa test/checksum để hợp thức hóa SQL đã bị thay đổi.

## Quy trình hai máy

1. Mỗi máy lưu/commit công việc hiện tại. Tích hợp cùng migration vào nhánh làm việc, không chép đè toàn bộ `src` và không sao chép `.env` hoặc `target` giữa máy.
2. So sánh nội dung/checksum migration ở hai phía; giữ cùng filename/version. Những migration mới tiếp theo dùng version chưa được bên nào sử dụng, lớn hơn lịch sử đã thống nhất.
3. Build từ output sạch để loại resource migration cũ. Chạy contract và integration tests trên database thử riêng trước khi chạy ứng dụng với database chung.
4. Kiểm tra read-only lịch sử Flyway và schema của database đích theo quyền truy cập sẵn có. Nếu V20261111 chưa có trong history, Flyway sẽ áp dụng file này trong lần migrate hợp lệ tiếp theo. Nếu đã được ghi nhận thành công với đúng checksum, Flyway không áp dụng lại.
5. Nếu cùng version nhưng SQL/checksum khác, hoặc column/index trùng tên nhưng khác cấu trúc, dừng để đối chiếu; không dùng `repair`, xóa history, baseline đè hoặc tắt validation. `IF NOT EXISTS` không xác minh hai object cùng tên có cùng cấu trúc.
6. Với database dùng chung, ưu tiên một lần khởi động/migrate có kiểm soát rồi mới khởi động backend còn lại, trên cùng release đã kiểm chứng. Source khác nhau vẫn có rủi ro 2FA, CLOSE_FRIENDS và notification worker dù migrations đã giống nhau.

Không chạy thủ công SQL V20261111 chỉ để bỏ qua Flyway. Nếu SQL đúng cấu trúc đã được áp dụng thủ công trước đó nhưng chưa có history, file có `IF NOT EXISTS`; integration test kiểm tra trường hợp này mà không sửa history bằng tay.

## Kiểm thử

Contract test không cần database:

```powershell
mvn --batch-mode -Dtest=MigrationHistoryContractTest test
```

PostgreSQL thử nghiệm biệt lập (Docker Desktop phải sẵn sàng):

```powershell
.\deploy\scripts\validate-empty-schema.ps1
.\deploy\scripts\validate-empty-schema.ps1 -PostgresVersion 17
```

Runner kiểm tra fresh schema, nâng cấp, history/checksum, khởi động đồng thời, bảo toàn notification/outbox và JPA writer hiện tại. Nó chỉ tạo/xóa database thử có tên ngẫu nhiên được test sở hữu, không dùng DB_URL của ứng dụng.

## Rollback

Giữ migration đã áp dụng trong source và giữ cột/index khi rollback phần Java tương thích. Không drop cột/index, xóa thông báo hoặc sửa migration history để quay về source trước. Khi V20261111 đã được chia sẻ giữa hai máy, thay đổi schema tiếp theo phải đi qua migration mới.

## Trạng thái kiểm chứng trong lượt đồng bộ schema ban đầu

- Contract mới đã chạy trước khi thêm SQL và thất bại đúng nguyên nhân thiếu file V20261111; sau khi đồng bộ SQL, cả 6 migration contract tests đạt.
- Đã chạy Maven với các arguments `--batch-mode`, `-Dit.test=FreshPostgresSchemaValidationIT,MigrationCompatibilityIT,NotificationOutboxMigrationIT`, `verify`: BUILD SUCCESS, compile/package đạt; 502 unit/contract tests tổng cộng, 501 đạt, 1 bỏ qua application context.
- 8 integration tests (1 fresh schema, 4 compatibility, 3 outbox migration) bị bỏ qua vì chưa cấu hình PostgreSQL thử nghiệm. Docker Desktop daemon không sẵn sàng; không báo các tests này là PASS.
- Đã so sánh file mới từng byte với ZIP và xác nhận 28 migration cũ không thay đổi. Chưa chạy migration hoặc kết nối database thật; chưa xác nhận history/schema của máy khác. Thay đổi source hiện tại là Implemented / Partially Tested.

## Cập nhật sau hợp nhất ZIP

Đã chạy build sạch với 516 unit/contract tests (515 đạt, 1 context skip), 13 integration tests được chọn trên PostgreSQL 18.6 tạm (13 đạt, không skip) và 40 kịch bản browser với fixtures. Các tests transaction/checkpoint và nâng cấp/chạy lại/khởi động đồng thời đã chạy thật. Xem `../quality/zip-merge-20261027.md` để biết command và giới hạn. Database thật của hai máy chưa được truy cập.
