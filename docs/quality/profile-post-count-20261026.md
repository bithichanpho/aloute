# Tổng bài viết trên hồ sơ — 26/10/2026

Confirmed Bug: PostService.countPostsByUser thêm điều kiện visibility=PUBLIC, khiến số trên hồ sơ bỏ sót FRIENDS/CLOSE_FRIENDS/ONLY_ME. ViewController đã lấy tổng riêng với trang nội dung, không đếm độ dài trang; phần này Already Correct.

Implemented: bỏ điều kiện audience khỏi truy vấn tổng. Giữ owner filter, loại HIDDEN và quy tắc sẵn có về bài gốc/quote có lời bình; không cộng lại các lượt đăng lại thuần trong collection reposts. Bài ảnh/video, bài chờ duyệt/flagged vẫn theo chính sách đếm trước đây. Không đổi quyền xem nội dung, không trả nội dung riêng tư, không tự duyệt bài. Số tổng dùng cùng giá trị cho chủ tài khoản, follower và người ngoài được phép mở hồ sơ.

Đổi mã nguồn: src/main/java/com/aloute/service/PostService.java (một truy vấn COUNT tại database, không tải bài vào RAM). Thêm src/test/java/com/aloute/controller/ProfilePostCountTest.java và src/test/java/com/aloute/schema/ProfilePostCountIT.java. Không sửa template/API/schema/Redis/migration hoặc dữ liệu người dùng. Giữ các sửa đổi đã có trong workspace.

Tested: `mvn -o -B -Dit.test=ProfilePostCountIT verify` với heap giới hạn và timezone UTC: BUILD SUCCESS. 489 unit/contract/architecture tests, 0 lỗi, 1 skipped; integration PostgreSQL 18.6: 1 test, 0 lỗi, 0 skipped. Log target/profile-post-count-tests-20261026.log.

Integration dùng database ngẫu nhiên riêng trên localhost, kiểm tra đủ 4 audience × APPROVED/PENDING/FLAGGED, ảnh/video, quote, community, visibility legacy null; không cộng tài khoản khác/HIDDEN/forward thuần. Tổng không đổi khi sửa audience, giảm khi ẩn/xóa, tăng khi thêm bài riêng tư. Xác minh chỉ gọi một aggregate count, không load dataset. Database thử đã thu hồi, server PostgreSQL thử đã dừng. Các tests privacy/revocation hiện có cũng đạt trong full suite. Không đo hiệu năng hoặc chạy app thật.

Partially Tested: chưa kiểm tra số cụ thể qua tài khoản đăng nhập trên app đang chạy; không khởi động backend vì startup có thể chạy migration trên database dùng chung. Không có migration mới cần chạy. Chạy lại app và tải lại hồ sơ để nạp logic Java mới. Rollback chỉ phần thay đổi điều kiện count, không reset toàn PostService.java đang có thay đổi trước.
