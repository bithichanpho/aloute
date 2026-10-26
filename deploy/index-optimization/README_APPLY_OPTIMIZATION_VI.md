# Hướng dẫn áp dụng tối ưu index ALOUTE (không tạo migration mới)

> **Đã thay thế.** `V20261104_1__durable_call_sessions.sql` và `V20261110__reconcile_document_indexes.sql` đã đưa schema cuộc gọi, bản sửa helper và các index vào Flyway, nên database mới cũng chạy được. Không cần chạy các script thủ công này nữa; nếu đã chạy trước thì cũng không sao. Xem `docs/DATABASE_MIGRATIONS.md`.

## Mục tiêu

- Giữ nguyên toàn bộ migration Flyway hiện có trong project, từ `V20260925` đến `V20261109`.
- Không thêm `V20261110` và không sửa checksum của migration đã chạy.
- Áp dụng phần tối ưu index tương thích với `SqlCriteriaCompiler` của nhánh CASO.
- Khôi phục hai hàm temporal helper ở một lần chạy riêng, để nếu tạo index lỗi thì không rollback mất phần sửa hàm.

## Thứ tự bắt buộc trên Supabase

1. Sao lưu database (ít nhất cần bản sao lưu trước khi tạo index trên dữ liệu thật).
2. Mở SQL Editor của đúng Supabase project mà ứng dụng ALOUTE đang dùng.
3. Mở và chạy **toàn bộ file** `01_restore_jsonb_temporal_helpers.sql` trong một lần. Không chỉ chọn một đoạn. Kết quả phải hiển thị hai chữ ký hàm `public.aloute_jsonb_local_datetime_key(jsonb)` và `public.aloute_jsonb_instant_key(jsonb)`, và hai cột `local_probe`/`instant_probe` không được `NULL`.
4. Chỉ sau khi bước 3 thành công, mở và chạy **toàn bộ file** `02_apply_18_optimized_indexes_no_flyway.sql` trong một lần. Kết quả tổng kết cần là `present_count = 18`, `missing_count = 0`.
5. Chạy `03_verify_optimized_indexes.sql` để xem từng index, định nghĩa index, helper function và lịch sử Flyway.
6. Kiểm tra lịch sử Flyway; database của bạn trước đó có `V20261109` thành công. Không nên có bản ghi `V20261110`; nếu có, dừng lại và kiểm tra history trước khi khởi động ứng dụng.

## Vì sao tách thành hai file

Lỗi trước đó là hàm `public.aloute_jsonb_instant_key(jsonb)` gọi helper `aloute_jsonb_local_datetime_key(jsonb)` không được resolve. File 01 định nghĩa lại helper và thay function instant để gọi rõ `public.aloute_jsonb_local_datetime_key(...)`, đồng thời đặt `search_path` ổn định; nó chạy probe để phát hiện lỗi trước khi tạo index. File 02 chỉ tạo index sau khi helpers đã được xác nhận tồn tại. Không dùng file SQL index gốc từ `aloute-optimize-index-db.zip` trực tiếp vì các phiên bản `V20261103` và `V20261104` trong nhánh đó đã được dùng cho các migration cuộc gọi của CASO.

## Những gì được / không được thay đổi

- Không có SQL nào trong hai file này ghi vào `public.flyway_schema_history`.
- Không tạo migration version mới.
- Không đổi payload/data JSONB của người dùng.
- Có `CREATE OR REPLACE FUNCTION`, `CREATE INDEX IF NOT EXISTS` và `ANALYZE`.
- Tạo index có thể làm tăng I/O và tạm thời chặn ghi trên bảng lớn; nên chạy vào thời điểm ít truy cập.
- `IF NOT EXISTS` không xác nhận rằng index trùng tên đã có đúng định nghĩa. Do đó, hãy đọc cột `indexdef` trong file 03 nếu index đã tồn tại từ lần chạy trước.

## Khởi động ứng dụng sau khi kiểm tra

Trong thư mục gốc project, dùng profile thông thường (không dùng `migration-recovery`):

```powershell
.\mvnw.cmd spring-boot:run
```

Nếu Flyway báo validation lỗi hoặc `V20261110` xuất hiện trong history, dừng lại, sao lưu `flyway_schema_history` và rà soát trước; không xóa record history hàng loạt.
