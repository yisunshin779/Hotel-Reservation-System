# Backend hệ thống đặt phòng khách sạn

Backend dùng Java 17, Spring Boot và PostgreSQL. Xem tiến độ, ma trận API và kết quả kiểm thử trong [báo cáo rà soát 23/11/2026](docs/backend_review_2026-11-23.md).

| Phạm vi | Dữ liệu và xác thực hiện tại |
| --- | --- |
| Account và Booking | JWT từ `hotel.account`. Chế độ canonical ghi đơn vào `hotel.reservations` và trả mã UUID. Chế độ cũ ghi vào `hotel.reservation`. |
| Payment và lưu trú | HTTP Basic hoặc Bearer đã liên kết với `hotel.users` khi bật chế độ canonical. Payment nhận UUID hoặc bigint. API lưu trú dùng bigint. |
| Report | HTTP Basic hoặc Bearer đã liên kết khi bật chế độ canonical. Quyền Admin hoặc Manager. Manager chỉ xem chi nhánh của mình. |

## Bật luồng Booking canonical

1. Áp dụng Flyway đến V13 trên database đã được kiểm tra. V12 gắn lựa chọn thanh toán với việc hủy đơn và thêm trạng thái retry cho outbox. V13 thêm thu hồi phiên JWT, audit Account, hàng chờ duyệt liên kết và outbox email Account. Backend dừng khi khởi động nếu bật canonical mà thiếu V11, có `hotel.account` mà thiếu V13, hoặc có bảng Payment/outbox nghiệp vụ mà thiếu V12. Ở chế độ vận hành (`HOTEL_SCHEMA_MODE=operational`, mặc định), backend còn dừng nếu thiếu hẳn một bảng mà Payment hoặc Checkout dùng, gồm `payment_intents`, `booking_confirmation_requests`, `invoice_delivery_requests`; thông báo nêu bảng và script tạo nó (`06_stay_contracts.sql`, `07_payment.sql`, đều nằm trong V8). Các kiểm tra đọc catalog (bảng, hàm `release_payment_selections`, thân `cancel_reservation` và `expire_holds`, các cột worker của hai outbox), nên vẫn chặn khi Flyway tắt và DB được migrate bằng CLI. Chỉ profile `report-demo` đặt `report-only`: DB báo cáo (`01_schema.sql` và dữ liệu báo cáo) được miễn các bảng Payment/Checkout, nhưng đối tượng đã có vẫn được kiểm tra và không được bật canonical. Đặt `HOTEL_CANONICAL_BOOKING_ENABLED=true` để chuyển search và hold sang cùng nguồn dữ liệu mà Payment sử dụng.
2. Khi khách mới xác minh email hoặc đăng ký mới bằng Google, backend tạo danh tính `GUEST`, hồ sơ khách và liên kết trong cùng giao dịch nếu email tương ứng còn trống trong `hotel.users`. Mật khẩu tiếp tục do Account quản lý. Danh tính mới chỉ xác thực nghiệp vụ bằng Bearer.
3. Với người dùng đã tồn tại, sau khi xác minh danh tính và quyền, tạo liên kết một-một trong `hotel.account_user_links`. Việc trùng email tự nó không tạo liên kết. Kiểm tra hồ sơ `customers` cho khách và chi nhánh cho nhân viên.
4. Dùng `GET /api/v1/bookings/search` để lấy mã loại phòng `hotel:<id>`. Tham số tùy chọn `soLuongPhong` lọc theo số phòng cần đặt. Kết quả có `tongGiaKyO` và `giaTheoDem` tính theo giá từng đêm, cùng cách tính với hold. Gửi mã đó tới `POST /api/v1/bookings/hold`. `soKhach` mặc định là 1. Header `Idempotency-Key` dạng UUID là bắt buộc: thiếu hoặc sai trả 400, gửi lại cùng nội dung trả cùng mã đơn, cùng khóa với nội dung khác trả 409. Search và hold dùng chung giới hạn `HOTEL_BOOKING_MAX_NIGHTS` (mặc định 90 đêm, là mặc định kỹ thuật vì repo chưa quy định giới hạn nghiệp vụ). Vượt giới hạn trả 400 với nội dung bắt đầu bằng `STAY_TOO_LONG`, trước khi truy vấn tồn, tính giá hoặc ghi giữ chỗ. Với hold, thứ tự là: khóa và cấu trúc nội dung, hồ sơ khách, tra replay theo khách và khóa, rồi mới đến giới hạn số đêm. Giới hạn chỉ áp cho hold mới: gửi lại đúng nội dung với khóa đã dùng vẫn trả mã đơn đã lưu sau khi giới hạn bị giảm; khóa đã dùng với nội dung khác trả 409 kể cả khi nội dung đó vượt giới hạn.
5. Dùng mã UUID trả về với `/api/reservations/{id}/payments`. Dùng `reservationId` bigint trong phản hồi Payment cho API lưu trú. `POST /api/reservations/{id}/cancel` cho phép chủ đơn hủy giữ chỗ chưa thu tiền, kể cả gửi lại cùng thao tác. Hủy đơn đã xác nhận hoặc đã trả tiền cần quy trình riêng.
6. Sau checkout, nhân viên `HOUSEKEEPING` đúng chi nhánh đọc phòng qua `GET /api/v1/housekeeping/rooms` (lọc tùy chọn `?cleaningStatus=DIRTY`) hoặc `GET /api/v1/housekeeping/rooms/{id}`; mỗi phòng có `version` (`hotel.rooms.version`). Sau đó gọi `PUT /api/v1/housekeeping/rooms/{id}/cleaning-status` lần lượt với `{"expectedStatus":"DIRTY","expectedVersion":<version>,"cleaningStatus":"CLEANING"}` và `{"expectedStatus":"CLEANING","expectedVersion":<version mới>,"cleaningStatus":"READY"}`. `expectedVersion` là bắt buộc. Phản hồi có `version` mới và `changed`. API chỉ cập nhật phòng trống, trả 409 `INVALID_CLEANING_TRANSITION` khi nhảy bước, 409 `STALE_CLEANING_STATUS` khi trạng thái hoặc phiên bản khác giá trị gửi lên (kể cả yêu cầu của lượt lưu trú trước khi phòng lại `DIRTY`), trả `changed=false` khi gửi lại đúng bước vừa áp dụng, và ghi audit (trạng thái và phiên bản trước/sau) khi thay đổi.

V11 giữ lại đơn cũ và các nguồn danh tính để chuyển tiếp. Chuyển đơn lịch sử và liên kết tài khoản cũ cần đối chiếu chủ đơn, giá, chi nhánh và quyền. Cần giữ chế độ mặc định trên môi trường cũ đến khi các liên kết cần thiết đã được xác minh.

## Chạy và kiểm thử

- Dùng [mẫu biến môi trường](.env.example) để cấu hình tiến trình. Mọi lần chạy backend cần khóa JWT ngẫu nhiên ít nhất 32 byte UTF-8. Giữ phòng theo luồng cũ cần Redis.
- Với DB trống hoặc DB có dữ liệu, làm theo [hướng dẫn Flyway](docs/backend_review_2026-11-23.md#c-database-và-triển-khai). Profile production dùng Hibernate `validate`.
- Từ thư mục này, chạy `mvn test` hoặc `.\mvnw.cmd test`. Riêng `StayIntegrationTest` cần `HOTEL_TEST_DB_URL` trỏ đến PostgreSQL riêng có database tên `hotel_lifecycle_test`. Suite xóa schema `hotel` trong database đó rồi dựng lại từ `01`, `02`, `05`–`08` và V12 trước khi Spring khởi động, vì khởi động ở chế độ vận hành cần đủ bảng Payment/Checkout. Các suite Payment, Invoice Adjustment, Report (chế độ `report-only`), Account/Booking và notification dùng PostgreSQL nhúng.
- Lần chạy `mvn -o '-Dhotel.build.directory=.local/backend-repair-build' clean package` sau đợt sửa bổ sung 24/11/2026 (mục J của báo cáo) đạt 159 test, 0 thất bại, 0 lỗi, 0 bỏ qua (mốc trước đợt bổ sung: 142; trước đợt 24/11: 124). Kịch bản tích hợp đi từ đăng ký và xác minh email tới đặt phòng, thanh toán, check-in, hóa đơn đóng, dọn phòng `DIRTY → CLEANING → READY` và email xác nhận, hóa đơn. Luồng kiểm thử dùng mail sender giả lập và thanh toán tiền mặt ghi nhận trong hệ thống.
- Email được gửi qua outbox bởi worker nền (`HOTEL_NOTIFICATIONS_ENABLED`), theo cơ chế ít nhất một lần. Mọi mốc thời gian điều phối hàng đợi (hạn gửi khi xếp hàng, kiểm tra đến hạn, claim, gia hạn lease, backoff, `sent_at`, tuổi hàng chờ) lấy từ PostgreSQL (`clock_timestamp()`), nên nhiều instance lệch đồng hồ vẫn thống nhất về lease. Worker lấy từng message ngay trước khi xử lý, gia hạn lease chỉ khi vẫn sở hữu claim và lease chưa hết, rồi mới gọi SMTP. `HOTEL_NOTIFICATIONS_MAX_ATTEMPTS` là số lần claim tối đa của một message; lỗi render, lỗi SMTP và lease hết mà không có kết quả đều tính một lượt. Message đã dùng hết lượt và hết lease chuyển `FAILED`/`ATTEMPTS_EXHAUSTED` và không bị claim lại. Email xác minh của tài khoản đang bị khóa được đóng `FAILED`/`ACCOUNT_LOCKED`; khi Admin mở khóa về `CHO_XAC_THUC` và token còn hạn, email được xếp lại với token hiện có. Lease (`HOTEL_NOTIFICATIONS_LEASE_SECONDS`, mặc định 300) phải dài hơn tổng timeout SMTP, nếu không backend dừng khi khởi động. Link xác minh và đặt lại mật khẩu đã quá hạn khi tới lượt gửi được đóng với `FAILED`/`TOKEN_EXPIRED`. JWT phát hành trước khi đặt lại mật khẩu, đổi vai trò, khóa hoặc mở khóa bị từ chối. Danh sách hợp đồng thay đổi và bảng theo dõi #1–#19 nằm ở [mục F–G của báo cáo rà soát](docs/backend_review_2026-11-23.md#f-hợp-đồng-thay-đổi-cho-người-gọi).
- Nếu IDE tự build vào `target/`, chạy Maven với `-Dhotel.build.directory=<thư mục khác>` để tránh class do trình biên dịch IDE ghi đè.

## Cấu hình bổ sung ngày 24/11/2026

| Biến | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `HOTEL_PAYMENT_MOCK_ENABLED` | `true`; `false` trong profile `production` | Cho phép ghi thanh toán và hoàn tiền giả lập `MOCK_QR`, `MOCK_CARD`. Khi tắt, mọi thao tác ghi giả lập trả 409 `MOCK_PAYMENT_DISABLED` trước khi ghi payment, refund, trạng thái đơn, audit hoặc outbox. Tiền mặt và đọc dữ liệu lịch sử không đổi. |
| `HOTEL_BOOKING_MAX_NIGHTS` | `90` | Số đêm tối đa cho search và hold canonical (phải ≥ 1). |
| `HOTEL_SMTP_CONNECT_TIMEOUT_MS`, `HOTEL_SMTP_READ_TIMEOUT_MS`, `HOTEL_SMTP_WRITE_TIMEOUT_MS` | `10000` mỗi biến | Timeout hữu hạn của JavaMail (`mail.smtp.connectiontimeout`, `timeout`, `writetimeout`). |
| `HOTEL_NOTIFICATIONS_LEASE_SECONDS` | `300` | Lease của một message trong outbox; phải lớn hơn tổng ba timeout SMTP. Lease tính theo giờ PostgreSQL. |
| `HOTEL_SCHEMA_MODE` | `operational`; `report-only` trong profile `report-demo` | `operational`: khởi động cần đủ bảng Payment/Checkout và V12. `report-only`: DB chỉ phục vụ báo cáo, không được bật cùng canonical. Giá trị khác làm khởi động thất bại. |

## Tài liệu nghiệp vụ

- [Payment](src/main/java/com/Hotel/Module_Payment/README.md)
- [Check-in, đổi phòng, check-out và hóa đơn](src/main/java/com/Hotel/Module_CheckInCheckOut/README.md)
- [Truy vết hợp đồng lưu trú](docs/stay_contracts.md)
- [Report và hướng dẫn chạy demo](src/main/java/com/Hotel/Module_Report/README.md)

Các báo cáo DOCX ngày 14/11 và 21/11 trong `docs/` ghi lại tiến độ tại thời điểm lập báo cáo.
