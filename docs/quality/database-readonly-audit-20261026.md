# Kiểm tra database chỉ đọc — 26/10/2026

Yêu cầu kiểm tra database được thực hiện trước khi người dùng chuyển trọng tâm sang khôi phục private. Chỉ mở kết nối READ ONLY; timeout mỗi truy vấn 10 giây, lock timeout 1 giây. Không nạp Spring application beans, chạy Flyway/migration, sửa dữ liệu/index/role, VACUUM hoặc xuất nội dung cá nhân/credentials.

## Verified

- PostgreSQL 18.6, database `aloutedb`, Render Singapore, khoảng 12 MB; kết nối audit có TLS và `transaction_read_only=on`.
- 20 bảng, 153 cột, 172 constraints (bao gồm NOT NULL của PostgreSQL 18), 122 indexes, một view social read API. Mọi index valid/ready, mọi constraint validated, mọi bảng có primary key.
- 28 migration thành công, cao nhất 20261110; checksum CRC32 chuẩn hóa line endings của cả 28 file source khớp database. Không thiếu named index được tạo và chưa bị drop trong chuỗi migration hiện tại.
- Kiểm tra dữ liệu trên toàn bộ 20 bảng. Lượt count có 26 users, 150 follow edges, 10 communities, 109 community memberships, 3 chat groups, 21 group memberships, 26 notifications, 2 call sessions. Document store 493 bản ghi ở lượt đầu, 494 ở lượt count sau; backend vẫn đang hoạt động nên số liệu các lượt không phải một snapshot toàn cục.
- Document collections ở lượt đầu: 143 posts, 80 comments, 107 likes, 72 reposts, 87 chat messages, 3 saved posts, 1 follow request. Không xuất payload/message/comment/profile thực tế.
- 17 foreign key checks: không có orphan. Các kiểm tra owner/post/comment/repost/community/group/receiver references bổ sung trên JSONB không tìm thấy reference thiếu; không có duplicate business tuples hay document ID/payload ID lệch.
- Không có sai lệch bộ đếm like/comment/repost/reply trong các kiểm tra đã chạy. Các enum hợp lệ theo source: ModerationStatus có FLAGGED và PostVisibility có ONLY_ME. Truy vấn enum ban đầu giả định sai danh sách được sửa theo enum source và chạy lại; kết quả cuối 0 invalid.
- 25 tập trường ngày giờ JSONB được kiểm tra; helper local datetime/instant xử lý được ISO, array cũ và từ chối ngày không hợp lệ. Không có required date thiếu hoặc date invalid trong kết quả cuối.
- Mọi sequence kiểm tra được đều không thấp hơn max ID; không gọi nextval/setval.
- Notification outbox rỗng, không dead letter/overdue delivery. Hai call sessions CANCELLED/MISSED đều COMPLETED, không ringing quá hạn/lease hết hạn hoặc participant thiếu.
- Không có blocked sessions hoặc deadlock tích lũy được ghi nhận. Autovacuum và data checksums bật. Lượt kiểm tra có 28/100 connections toàn cluster, không idle transaction quá một phút. Đây là quan sát tức thời, không phải load test.
- PUBLIC schema chỉ có USAGE, không cấp CREATE; không thấy table privilege cấp trực tiếp cho PUBLIC. Không có RLS trên các bảng ứng dụng; phân quyền hiện do backend đảm nhiệm, không coi việc thiếu RLS tự nó là lỗi.

## Performance / privilege risks cần cân nhắc

1. `pending_users` có hai unique indexes tương đương trên verification token: `pending_users_verification_token_key` và `uk_pending_users_verification_token`. Đây là redundancy được xác nhận, chưa đo tác động và không tự drop index.
2. FK `users.role_id` không có index với cột đó ở đầu. Bảng users chỉ có 26 dòng ở thời điểm kiểm tra; chưa có bằng chứng truy vấn chậm, không tự thêm index.
3. Role ứng dụng không superuser/bypass RLS/replication nhưng có CREATEDB/CREATEROLE. Nên cân nhắc role runtime quyền tối thiểu tách khỏi role migration; không tự thay quyền hay credentials.

## Partially Tested / chưa xác nhận

- Probe Hibernate standalone chỉ validate schema, dùng connection read-only, đã thất bại với SchemaManagementException. Chưa xác định nguyên nhân chính xác của probe (không dùng toàn bộ cấu hình naming/wiring của Spring Boot), không kết luận đó là schema lỗi và không báo JPA validation PASS.
- Không kiểm tra physical corruption bằng amcheck, backup/PITR/restore trên Render, dump toàn database, hiệu năng tải lớn, replica/failover hoặc chức năng privacy bằng phiên đăng nhập thật.
- Không có thay đổi database nào từ audit này. Những rủi ro trên chưa được sửa vì yêu cầu là kiểm tra.

## Evidence

17 nhóm metadata queries + 43 nhóm aggregate data checks + 7 nhóm follow-up (enum follow-up thay kết quả enum ban đầu). Tất cả đều hoàn thành SQL thành công; statement errors 0. Kết nối được đóng sau mỗi chương trình audit; rollback sau mỗi query.

- `target/database-audit-metadata-20261026.json`
- `target/database-audit-data-20261026.json`
- `target/database-audit-followup-20261026.json`
- `target/database-audit-source-comparison-20261026.json`
- Query definitions tương ứng `target/database-audit-*-queries.json`
- `target/database-schema-readonly-20261026.log` (probe validation chưa qua)

Trạng thái: **Tested với metadata và logical integrity đã liệt kê; PARTIAL với kiểm chứng database toàn diện**. Không tuyên bố mọi dữ liệu/lỗi nghiệp vụ/physical storage/backup đều đã được chứng minh đúng.
