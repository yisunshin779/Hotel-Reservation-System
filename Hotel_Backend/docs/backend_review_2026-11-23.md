# Rà soát và đồng bộ backend khách sạn

Xem [README backend](../README.md) để tra nhanh hướng dẫn chạy và tài liệu từng module.

Ngày 23/11/2026. Mốc khảo sát là `89588de`, đã đồng bộ main `6207548`. Working tree còn các thay đổi đang phát triển cho Payment, điều chỉnh hóa đơn, tài liệu và test. Báo cáo dưới đây mô tả mã nguồn đang có trong working tree, bao gồm các module đó.

Đã triển khai xác thực lại trạng thái và vai trò cho JWT của Account/Booking, liên kết Google có xác nhận, khóa giao dịch khi giữ phòng, cấu hình theo môi trường và Flyway. Việc chuyển dữ liệu đơn và tài khoản cần đối chiếu dữ liệu lịch sử. Quy trình từ Booking API đến hóa đơn vẫn thiếu kết nối giữa hai nguồn đơn.

## A. Kiến trúc trước và sau

| Module | Nguồn dữ liệu | ID | Xác thực | Phụ thuộc và kết quả rà soát |
| --- | --- | --- | --- | --- |
| Account, Auth, Admin | JPA `hotel.account`, `hotel.verification_token` | UUID | JWT | JWT nay tra UUID và dùng vai trò, trạng thái hiện tại trong DB. Google lưu `sub` trong nguồn Account hiện hành. |
| Booking | JPA `hotel.room_type`, `hotel.reservation` | Mã loại phòng chuỗi, đơn UUID | JWT | Giữ cổng search/hold, dữ liệu và thời hạn 15 phút. Khóa hàng loại phòng cho tới khi transaction kết thúc. Redis tiếp tục lưu TTL theo thiết kế hiện tại. |
| Payment | `hotel.reservations`, `payments`, `payment_intents`, `booking_confirmation_requests` | Đơn và Payment bigint, intent UUID | HTTP Basic, `hotel.users` | Khóa đơn và kiểm tra quyền chủ đơn hoặc lễ tân đúng chi nhánh. Dùng chung giao dịch với Check-out. |
| Check-in và đổi phòng | `hotel.reservations`, `reservation_details`, `reservation_nights`, `room_assignments`, `stay_guests` | bigint | HTTP Basic, `hotel.users` | Tra trạng thái, lịch đến, phòng, nghĩa vụ thanh toán và phạm vi chi nhánh. |
| Check-out và Invoice | `hotel.reservations`, `payments`, `refunds`, `invoices`, `invoice_lines`, `invoice_adjustments` | bigint | HTTP Basic, `hotel.users` | Dùng ledger Payment. Trigger bảo vệ hóa đơn gốc. Điều chỉnh có nguồn và quyền riêng. |
| Report | Truy vấn JDBC từ `hotel` theo loại báo cáo | Chi nhánh, loại phòng, đơn và hóa đơn bigint | HTTP Basic, `hotel.users` | Admin xem phạm vi toàn hệ thống. Manager bị giới hạn theo chi nhánh. |

### Khả năng chuyển tài khoản sang `hotel.users`

| Chức năng | JPA hiện tại | Điều kiện để chuyển |
| --- | --- | --- |
| Đăng ký, đăng nhập | UUID, email duy nhất, BCrypt, trạng thái hoạt động hoặc chờ xác minh | Giữ UUID tham chiếu cho API Admin, giữ nguyên hash, đối chiếu biến thể BCrypt có prefix, email trùng hoặc khác hoa thường và các cột bắt buộc. |
| Xác minh và đặt lại mật khẩu | `verification_token.account_id` trỏ UUID, lưu loại và thời hạn | `auth_tokens.user_id` trỏ bigint, lưu hash, purpose và thời điểm dùng. Cần ánh xạ token còn hiệu lực, giữ TTL và tính dùng một lần. |
| Hồ sơ | Họ tên, điện thoại và CCCD duy nhất | `users` có họ tên, điện thoại. Cần xác định cách lưu và biểu diễn CCCD cho cả khách và nhân viên. |
| Google | Mật khẩu nullable, `google_sub` duy nhất sau V10 | `users.password_hash` bắt buộc trong schema đang có. Cần biểu diễn tài khoản Google-only và chuyển liên kết theo bản đồ UUID–bigint đã xác minh. |
| Role và khóa | Vai trò `ROLE_*`, `CHO_XAC_THUC` hoặc `BI_KHOA`, khóa tạm bằng bộ đếm và thời hạn | `users` có FK role, `active`, `email_verified_at`, `locked_until`, `deleted_at`. Giữ riêng khóa đăng nhập mật khẩu và khóa Admin. Phải đối chiếu `ROLE_USER` với `GUEST` và các quyền nhân viên. |
| Customer, Employee, Branch | Account chưa có FK khách, nhân viên hoặc chi nhánh | `customers.user_id` là FK duy nhất tùy chọn. Nhân viên được biểu diễn bằng `users.role_id` và `users.branch_id` trong schema đã khảo sát. Phải gán từ dữ liệu đã xác minh, đặc biệt với người dùng có quyền chi nhánh. |

Phương án A–B–JWT–B–A khả thi về thiết kế. Việc đưa A và B vào vận hành cần một bản sao dữ liệu đã kiểm soát, đối chiếu nguồn thực tế và xác nhận các trường hợp mâu thuẫn. Ghép email trực tiếp có thể gán nhầm chủ đơn hoặc quyền của nhân viên. Backend hiện có hai miền danh tính, mỗi miền tiếp tục xử lý quyền bằng dữ liệu của mình.

### Trạng thái đơn và giao dịch

- `hotel.make_reservation` có sẵn khóa advisory theo loại phòng, kiểm tra tồn từng đêm, tính giá qua `nightly_rate` và ghi đơn, chi tiết, giá theo đêm trong cùng transaction. Booking API cũ dùng tồn tổng hợp, mã loại phòng chuỗi và không lưu giá chốt, khách hoặc chi nhánh.
- Payment trong ứng dụng khóa hàng đơn, đọc thời gian DB sau khi lấy khóa, kiểm tra trạng thái trước khi thu, ghi nghĩa vụ/giao dịch và xác nhận trong một transaction. Gọi lại intent xử lý theo trạng thái đã lưu. Outbox xác nhận nằm cùng transaction với thanh toán. Hệ thống đang dùng Payment mô phỏng và chưa có callback ngân hàng thật.
- `expire_holds` sử dụng `FOR UPDATE SKIP LOCKED` và gọi `cancel_reservation`. Luồng SQL hủy đơn chấp nhận cả đơn `CONFIRMED` và quy định hoàn tiền riêng. Khi chuyển Booking API, cần chốt đường ghi trạng thái ở cấp quyền database vì `02_operations.sql` còn các routine trực tiếp như `confirm_mock_qr`.
- Booking UUID dùng transaction riêng với khóa loại phòng. Hai yêu cầu tranh phòng cuối cùng được kiểm tra nối tiếp trên PostgreSQL. Cron phía Booking và `expire_holds` phía Payment hiện xử lý các bảng khác nhau.
- Hướng chuyển là lập ánh xạ mã loại phòng theo chi nhánh và chủ đơn theo tài khoản đã xác minh, giữ UUID duy nhất trên đơn canonical, rồi cho Booking gọi `make_reservation`. Với đơn cũ, chỉ nhập khi xác minh được chủ đơn, chi nhánh, tình trạng và giá lịch sử. Các đơn thiếu dữ liệu cần giữ quyền tra cứu lịch sử riêng. Cần khóa luồng ghi cũ khi cutover và dùng khóa idempotency có ràng buộc DB cho request tạo đơn mới.

## B. Tệp thay đổi

| Module | Tệp | Thay đổi | Lý do |
| --- | --- | --- | --- |
| Booking | `BookingService.java`, `RoomTypeRepository.java`, `Reservation.java`, `BookingController.java` | Khóa hàng loại phòng bằng PostgreSQL ở mức Read Committed, kiểm tra đầu vào, khởi tạo thời điểm tạo đơn. | Ngăn yêu cầu thứ hai đọc tồn trước khi giao dịch trước commit, khắc phục thiếu thời điểm tạo trong cột bắt buộc. |
| Account và Auth | `JwtTokenProvider.java`, `JwtAuthenticationFilter.java`, `SecurityConfig.java` | Secret môi trường, HS256, kiểm tra hạn và UUID, lấy role và trạng thái hiện tại. Chỉ gắn JWT filter vào chain Account/Booking. | Áp dụng khóa Admin và đổi vai trò với token đã cấp, tránh dùng nhầm danh tính JPA trong chain JDBC. |
| Account và Auth | `Account.java`, `AccountRepository.java`, `VerificationTokenRepository.java`, `AccountController.java`, `AccountService.java`, `AuthService.java` | Liên kết đã xác thực theo `sub`, khóa khi sửa account và dùng token, kiểm tra mục đích xác minh, giữ trạng thái khóa Admin. | Tránh tự liên kết bằng email, ghi đè liên kết hoặc kích hoạt lại tài khoản bị Admin khóa. |
| Cấu hình | `EmailService.java`, `application*.yaml`, `.env.example`, hai `docker-compose.yml` | URL frontend và backend, SMTP, Google Client ID, JWT secret lấy từ cấu hình. Tách dev và production. Dùng cổng host cấu hình được. | Loại bỏ secret mặc định và URL cứng trong luồng email. |
| Database | `pom.xml`, `db/migration/V8__hotel_operations_baseline.sql`, `V9__legacy_jpa_tables.sql`, `V10__google_subject.sql` | Flyway với baseline tường minh, bootstrap JPA, cột và unique index cho Google. | Quản lý lịch sử schema và nâng cấp cộng thêm. |
| Kiểm thử | `AccountBookingIntegrationTest.java`, `JwtTokenProviderTest.java`, `src/test/resources/application.properties` | Thêm test HTTP, cạnh tranh giữ phòng, kiểm tra JWT, Google và Flyway. | Kiểm chứng các ranh giới mới trên PostgreSQL fixture. |

## C. Database và triển khai

Các bảng nghiệp vụ có PK bigint identity và FK qua khách, chi nhánh, phòng, đơn, thanh toán và hóa đơn. `reservations.code` duy nhất, `invoices.reservation_id` duy nhất, `payment_intents.id` UUID và `payment_intents.payment_id` duy nhất. Các bảng JPA dùng UUID cho Account, VerificationToken, Reservation và chuỗi cho RoomType. Flyway V8 đóng băng cấu trúc nghiệp vụ trong các script 01, 02 và 05–08, gồm enum, `btree_gist`, constraint ngày, index, khóa chống phân phòng trùng và trigger bảo vệ sổ tài chính. V9 tạo bốn bảng JPA nếu chưa có. V10 thêm cột nullable và index duy nhất, giữ lại tất cả hàng hiện có. Script dữ liệu mẫu và báo cáo demo có quy trình riêng.

### Database trống

1. Provision PostgreSQL và tài khoản migration có quyền tạo schema, extension cần thiết.
2. Đặt `SPRING_PROFILES_ACTIVE=production`, điền cấu hình trong `Hotel_Backend/.env.example` qua môi trường thực thi và đặt `HOTEL_FLYWAY_ENABLED=true`. Hibernate kiểm tra schema bằng `validate`.
3. Cấp dữ liệu nghiệp vụ như chi nhánh, loại phòng, phòng và vai trò qua quy trình seed được phê duyệt. Bản migration chỉ tạo schema.

### Database đã có dữ liệu

1. Sao lưu và phục hồi vào một DB thử nghiệm. So sánh catalog với V8, đặc biệt các function, trigger và constraint qua script 08. Xác minh các bảng JPA và schema chứa chúng. DB có layout khác cần script hòa giải phù hợp trước khi baseline.
2. Chạy Flyway CLI với `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` cho đúng DB, `FLYWAY_SCHEMAS=hotel`, `FLYWAY_BASELINE_VERSION=8` và `FLYWAY_LOCATIONS=filesystem:Hotel_Backend/src/main/resources/db/migration` khi chạy từ gốc repo. Với DB đã đối chiếu qua 08, chạy `flyway baseline`, sau đó `flyway migrate` và `flyway validate`. Baseline tường minh tránh đánh dấu nhầm trạng thái của một DB chưa được đối chiếu. [Tài liệu Flyway baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline)
3. So sánh số lượng đơn, giao dịch, hóa đơn và tài khoản trước/sau. Kiểm tra dữ liệu JPA có UUID, hash, trạng thái và token còn hiệu lực. Nếu bật Google, kiểm kê tài khoản `auth_provider='GOOGLE'` chưa có `google_sub`, xác minh quyền sở hữu trước khi phục hồi truy cập. Không dùng email làm bằng chứng duy nhất để điền `sub`.
4. Triển khai backend sau khi schema tương thích. `baseline-on-migrate` tắt và `clean` bị vô hiệu trong cấu hình ứng dụng. V8–V10 là lịch sử đã đóng băng, các thay đổi tiếp theo dùng phiên bản mới.

### Cấu hình chạy

- Xuất biến như trong `.env.example`. Spring Boot đọc biến môi trường của tiến trình. Mỗi môi trường dùng khóa JWT ngẫu nhiên riêng, dài từ 32 byte UTF-8.
- Profile `development` hỗ trợ Hibernate update khi dùng DB local. Chọn `HOTEL_DDL_AUTO=validate` nếu chạy Flyway trên DB phát triển. Profile `production` luôn dùng `validate` theo mặc định. Profile `report-demo` tiếp tục dùng database demo riêng và cần JWT secret để chạy backend.
- Dùng một PostgreSQL cho mỗi cổng host. Với DB stack, chạy PostgreSQL từ `Hotel_Database`, rồi chạy riêng Redis từ `Hotel_Backend` nếu chạy Booking. Nếu cần song song hai DB cục bộ, đặt `HOTEL_DB_HOST_PORT` khác nhau cho từng stack và trỏ `HOTEL_DB_URL` tới đúng DB. PostgreSQL hiện hữu trên cổng 5432 không được dùng trong test. Docker Compose chưa được khởi chạy trên máy này.
- Google chỉ bật khi đặt `HOTEL_GOOGLE_CLIENT_ID`. Frontend OAuth phải dùng Client ID phù hợp. URL backend công khai dùng cho liên kết xác minh, URL frontend dùng cho trang đặt lại mật khẩu và CORS.

## D. API và ảnh hưởng frontend

- Giữ đường dẫn, payload và hình dạng phản hồi đăng ký, đăng nhập, xác minh, đặt lại mật khẩu, hồ sơ, Admin, Booking, Payment, Stay, Invoice và Report. Token nội bộ mới thêm claim `account_id` và vẫn trả `token`, `type`, `email`, `vaiTro`. Phiên cũ cần đăng nhập lại sau khi thay khóa và áp dụng claim mới.
- Thêm `POST /api/v1/accounts/me/google` nhận `{"idToken":"..."}` cùng Bearer token. Phản hồi thành công là HTTP 204. Frontend có thể gọi từ thao tác xác nhận liên kết trong hồ sơ.
- `POST /api/v1/auth/google` giữ payload và phản hồi thành công. Tài khoản mới có email Google đã xác minh được tạo với `ROLE_USER`. Tài khoản cũ trùng email phải liên kết trước qua phiên nội bộ. Tài khoản Google-only cũ thiếu `sub` cần phục hồi đã xác minh trước khi bật luồng mới trên môi trường có dữ liệu đó.
- Booking search và hold hiện tiếp tục theo chính sách JWT của catch-all chain. Payment, Stay, Invoice và Report tiếp tục dùng Basic và các kiểm tra ID, role, chi nhánh của miền `hotel.users`.

## E. Kiểm thử

| Nhóm test | Passed | Failed | Skipped | Chưa chạy |
| --- | ---: | ---: | ---: | --- |
| Billing unit | 3 | 0 | 0 | 0 |
| JWT unit | 3 | 0 | 0 | 0 |
| Stay integration | 35 | 0 | 0 | 0 |
| Invoice adjustment integration | 8 | 0 | 0 | 0 |
| Payment integration | 15 | 0 | 0 | 0 |
| Report integration | 25 | 0 | 0 | 0 |
| Account, Booking và Flyway integration | 7 | 0 | 0 | 0 |
| **Tổng số test tự động đã chạy** | **96** | **0** | **0** | **0** |
| End-to-end từ Booking API qua Payment đến hóa đơn | 0 | 0 | 0 | 1 luồng |

Maven `package` đã thành công với 95 test trên PostgreSQL thử nghiệm riêng, gồm đủ 35 test lưu trú trước đây phụ thuộc `HOTEL_TEST_DB_URL`. Lần chạy tập trung sau đó có 10 test thành công, gồm 9 test đã có và 1 test API bổ sung. Lần chạy lại toàn bộ sau cùng bị gián đoạn. Bảng tính theo test riêng biệt trong hai lần chạy thành công.

Bài kiểm thử bổ sung tạo đơn qua `hotel.make_reservation`, chọn và thu tiền mặt bằng HTTP, xác nhận lễ tân sai chi nhánh nhận HTTP 403, làm thủ tục lưu trú đúng chi nhánh và đối chiếu đơn `CHECKED_OUT` với hóa đơn `CLOSED` và một giao dịch `PAID`. Luồng đăng ký, email xác minh và đăng nhập đã được kiểm tra riêng qua HTTP.

Flyway đã chạy trên DB trống với Hibernate `validate`. Fixture nâng cấp từ 08 đã kiểm tra việc từ chối migration khi thiếu baseline, thực hiện baseline, áp dụng V9–V10, chạy lại với 0 migration mới và đối chiếu hash BCrypt, UUID và trạng thái đơn. Tất cả các bước được chạy trên database thử nghiệm. Test Google mô phỏng kết quả của thư viện xác minh token.

## F. Phần cần hoàn tất

- **Chuyển Booking và Auth.** Dữ liệu PostgreSQL dùng chung chưa được cấp để đối chiếu trong lượt này. Cần bản sao đã kiểm soát kèm mapping chủ đơn, chi nhánh, loại phòng, giá và trạng thái lịch sử. Sau khi backfill có kiểm tra, chuyển ghi mới theo một nguồn và phát JWT tới cùng miền danh tính, giữ UUID tương thích API.
- **Google-only cũ.** Cần xác minh số lượng tài khoản và quy trình khôi phục trước khi đặt Google Client ID trên môi trường cũ. Test đã mô phỏng kết quả xác minh Google. Kiểm thử OAuth với Google thật cần Client ID và tài khoản test của môi trường.
- **Quy trình đầy đủ từ đăng ký đến checkout.** Kiểm thử Auth và luồng đơn canonical đi qua các entry point khác nhau do chưa có mapping. Bài kiểm thử cuối cần một khách đăng ký, đăng nhập, gọi Booking API, thanh toán đúng UUID ánh xạ, thử check-in khác chi nhánh và xác nhận hóa đơn, giao dịch và trạng thái trong cùng hệ thống xác thực.
