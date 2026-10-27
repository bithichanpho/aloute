# Bảo vệ migration giữa các máy — 26/10/2026

## Audit và phân loại

| Phân loại | Hiện trạng xác minh | Xử lý |
|---|---|---|
| Already Correct | 28 SQL migrations hiện tại khớp lịch sử database đã audit; có V20261104.1 khôi phục call schema và V20261110 đồng bộ helpers/indexes | Không sửa/đổi tên SQL cũ, không thêm một migration bù trùng chức năng |
| Already Correct | Flyway validate-on-migrate và out-of-order đã bật; JPA mặc định validate | Giữ để V20261104.1 vẫn áp dụng được sau các version cao hơn |
| Confirmed Bug | CI dùng localhost/aloute_schema_validation nhưng một số integration test chỉ nhận 127.0.0.1/aloute_quality_* | Đồng bộ URL test và tên database |
| Confirmed Bug | CI đặt Hibernate create-drop, không kiểm chứng schema do Flyway tạo giống cách chạy production | Chuyển CI sang validate |
| Architecture / release risk | Chưa có khóa bảo vệ SQL đã triển khai hoặc version bị dùng lại; build cũ có thể còn resource sau khi thay source | Thêm manifest và 5 contract tests, LF cho SQL |
| Requires Integration Testing | PostgreSQL 17 và GitHub Actions thực tế | Đã cấu hình matrix 17/18; local mới xác minh PostgreSQL 18.6 |

Không có migration SQL cũ nào bị sửa, không chạy repair/baseline/migrate/clean lên database Render. Không thay ứng dụng private, REST/GraphQL/WebSocket contracts hoặc format dữ liệu.

## Implemented

- `.gitattributes`: cố định LF cho SQL migration.
- `src/main/resources/application.yml`: ghi rõ `spring.flyway.clean-disabled: true`; giữ validate và cơ chế tương thích out-of-order hiện có.
- `src/test/resources/migration-baseline-20261110.properties`: pin SHA-256 chuẩn hóa line endings/BOM của 28 SQL files đã có.
- `src/test/java/com/aloute/schema/MigrationHistoryContractTest.java`: 5 kiểm tra version Flyway không trùng, SQL cũ không bị đổi/xóa/rename, migration mới cao hơn mốc đã triển khai, resources runtime khớp source và các cấu hình an toàn còn bật.
- `DisposablePostgres.java`: chỉ tạo database tên ngẫu nhiên do test sở hữu, trên endpoint loopback aloute_quality_* được opt-in rõ ràng; cleanup chỉ database đã tạo.
- `MigrationCompatibilityIT.java`: 4 kịch bản nâng cấp giữ dữ liệu post/call, migration bù chạy muộn, checksum sai dừng trước khi thay dữ liệu/history, và hai migrator khởi động đồng thời. Có kiểm tra chạy lại không áp dụng migration lần nữa.
- `FreshPostgresSchemaValidationIT.java`: sử dụng database mới hoàn toàn do test sở hữu, sau đó Hibernate validate entity mappings.
- `.github/workflows/ci.yml`: matrix PostgreSQL 17/18, URL test khớp safety guards, Hibernate validate, timezone test UTC, tên coverage artifact riêng từng phiên bản.
- `deploy/scripts/validate-empty-schema.ps1`: chọn PostgreSQL 17/18, cổng loopback khả dụng, chỉ chạy contract + migration compatibility tests thay vì vô tình bootstrap app thật; chỉ dọn container đã tạo thành công. Hỗ trợ Maven offline.
- `docs/DATABASE_MIGRATIONS.md`: policy source/build/history, lệnh kiểm tra và xử lý checksum khác nhau giữa các máy; làm rõ schema public.

Không tự tái tạo manifest để chấp nhận SQL lịch sử đã bị sửa. Nếu lịch sử thực tế đã khác checksum, cần phục hồi/đối chiếu artifact lịch sử trước; không có cách tự sửa chung nào bảo đảm đúng cho mọi database đã lệch.

## Tested — kết quả thực tế

Docker Desktop daemon không hoạt động dù thử khởi động; không thay Docker config, sửa WSL toàn cục hoặc dừng Redis. Thay thế môi trường test bằng PostgreSQL **18.6** từ Ubuntu package repository đã có cấu hình trong WSL: tải và giải nén package vào runtime riêng, không cài system package/service. Server chỉ bind 127.0.0.1 trên cổng khả dụng 40497, dữ liệu hoàn toàn synthetic.

| Kiểm tra | Kết quả |
|---|---|
| Contract gate ban đầu | 4 PASS; sau bổ sung quy tắc version mới có tổng 5 contract tests trong full gate |
| Migration gate riêng | 4 unit + 9 integration PASS, 0 errors/failures/skips |
| Full `mvn -o -B verify` | **BUILD SUCCESS** |
| Surefire toàn bộ | **449 tổng; 448 PASS, 1 skipped**, 0 errors/failures |
| Failsafe toàn bộ | **71 tổng; 65 PASS, 6 skipped**, 0 errors/failures |
| Fresh schema + Hibernate validate | PASS trên PostgreSQL 18.6 |
| Nâng cấp từ V20261102, bảo toàn post/call/users và reserve call ID | PASS |
| V20261104.1 áp dụng sau V20261110 | PASS, dữ liệu mẫu giữ nguyên |
| Cố ý đổi checksum, không repair hoặc đổi dữ liệu/history | PASS |
| Hai migrator đồng thời + khởi động lại | PASS, không trùng version và lần chạy lại áp dụng 0 migration |
| Kiểm tra migration lịch sử call/date/email cũ | PASS |
| PowerShell parser + YAML parser | PASS; YAML matrix đọc được 17 và 18 |

Lượt thử đầu gặp `invalid value for parameter TimeZone: Asia/Saigon` ở PostgreSQL package thử nghiệm trước khi chạy SQL migration. Chuyển **JVM test** sang UTC và chạy lại thành công; không đổi timezone nghiệp vụ của ứng dụng. Các log ERROR do test cố ý tạo duplicate CALL hoặc checksum drift là negative tests được xác nhận PASS, không phải migration production thất bại.

Các bài bỏ qua: `AlouteApplicationTests.contextLoads` do DB_URL không truyền cho suite; 2 Redis cache tests, 1 Redis mirror test, 2 performance tests và 1 realtime browser smoke do chưa opt-in dịch vụ riêng. FullBackendContextIT và các HTTP/auth integration opt-in database đã chạy trên database test; không dùng credentials production.

Log: `target/migration-contract-20261026.log`, `target/migration-postgres18-verify-utc-20261026.log`, `target/migration-full-verify-20261026.log`. Tổng kết lấy từ **log của lượt chạy hiện tại**; thư mục XML report có thể còn file của lượt cũ. JAR đã được đóng gói trong verify. PostgreSQL test riêng đã dừng sau kiểm chứng; không tắt WSL hoặc Redis đang dùng.

## Giới hạn và rollout

- PostgreSQL 17/GitHub Actions matrix: **Not Started trên CI thực tế**, cần CI chạy khi người dùng đưa thay đổi lên repository. Không tuyên bố mọi OS/database cũ đều đã kiểm chứng.
- Script Docker: parser PASS, chưa chạy end-to-end vì daemon chưa có; test Java tương ứng đã chạy bằng PostgreSQL WSL riêng.
- Existing production history drift, backup/restore/PITR: chưa thao tác; giữ quy trình đối chiếu/backup được review trước khi reconciliation.
- Dùng cùng source/release artifact giữa các máy; cấu hình local/credentials riêng. Chạy kiểm tra bằng lệnh trong DATABASE_MIGRATIONS.md trước release. Không copy target cũ hoặc .env từ máy khác.

Trạng thái: **Implemented / Tested trên PostgreSQL 18.6; Partially Tested cho PostgreSQL 17, CI và Docker**. Rollback chỉ thay đổi test/CI/config bổ sung của lát cắt; không có migration mới hay data rollback. Không push/merge/deploy.
