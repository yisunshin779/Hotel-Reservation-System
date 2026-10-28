# Module Check-in / Đổi phòng / Check-out — UC16 / UC17 / UC19

Phần triển khai nằm trong `src/main/java/com/Hotel/Module_CheckInCheckOut`.
Stack giữ theo repository: Java 17, Spring Boot 3.2, PostgreSQL 15+.
Frontend React/Vite hiện là các trang placeholder; thay đổi này cung cấp backend REST API và bản xuất hóa đơn HTML, chưa xây giao diện vận hành.

## Kết quả khảo sát và phạm vi

Nguồn yêu cầu ưu tiên là `../../Sứ Thanh Hóa - 172889 - Báo cáo BTL Phân tích thiết kế hệ thống.docx` (đường dẫn tính từ `Hotel_Backend`). Đối chiếu UC16–19, OC-16.01/02, OC-17.01, OC-19.01/02/03 và OC-11.01/02, cùng biểu đồ lớp/trình tự UC16/17/19. Xem `docs/stay_contracts.md` để biết cách ánh xạ và các quyết định triển khai còn cần cấu hình.

- Backend ban đầu chỉ có các class rỗng của `Module_DatPhong`, chưa có entity JPA hoàn chỉnh, service/controller chạy được, authentication hay transaction nghiệp vụ.
- `ReservationRepository` cũ khai báo JPA nhưng thiếu import và entity ánh xạ. Giữ chữ ký hàm tra cứu thành integration interface, chưa triển khai JPA/UC07, để không chặn build.
- Entry point được bổ sung `@SpringBootApplication`; driver MySQL được thay bằng PostgreSQL để khớp `Hotel_Database`. JPA vẫn được giữ, không tự tạo/sửa schema.
- Tái sử dụng 26 bảng và các enum nền; migration 06 bổ sung hồ sơ người ở, snapshot giá assignment, điều chuyển tồn phòng và yêu cầu gửi hóa đơn. Dùng JDBC qua repository vì model đặt phòng hiện chưa ánh xạ; không tạo các entity JPA trùng lặp.
- Redis chưa cần cho UC16/UC19 nên tắt autoconfiguration Redisson; dependency của module đặt phòng vẫn giữ nguyên. Khi module đó sẵn sàng, cấu hình lại Redis/autoconfiguration trong tích hợp chung.
- Phạm vi triển khai là UC16/17/19 và nhánh hoàn chênh lệch tại quầy liên quan UC11. Không triển khai toàn bộ UC02/07/08/11/15/18/21/27, no-show, cổng thanh toán thật hoặc hóa đơn điều chỉnh. Module xác thực chung vẫn phải cung cấp principal đã kiểm chứng; không có cơ chế bypass.

## Kiến trúc

```text
controller/  CheckInController, CheckOutController, RoomChangeController,
             StayErrors và InvoiceView xuất bản in
dto/         Request/response riêng cho check-in, check-out, đổi phòng,
             thu/hoàn tiền, phòng và hóa đơn
entity/      Reservation, ReservationDetail, Room, RoomAssignment, StayGuest,
             Payment, Refund, Invoice, InvoiceLine, Bill và các kiểu nghiệp vụ
repository/  StayRepository và JdbcStayRepository
service/     CheckInService, CheckOutService, RoomChangeService, RefundService,
             BillingService, InvoiceService, CheckInValidator, ReceptionistContext
```

Controller nhận DTO đã validation, gọi service và trả response DTO; không truy cập repository trực tiếp. DTO nằm trong các file riêng, không lồng trong controller/service. Entity cũng được tách từng file thay vì gom trong `Stay.java`.

Các model trong `entity` là Java record/enum và quy tắc nghiệp vụ, không phải `@Entity` JPA; module tiếp tục dùng JDBC và schema/migration SQL hiện có. Entity không import Spring, JDBC hay JPA. Service quản lý nghiệp vụ và transaction; repository phụ trách đọc/ghi DB. `ReceptionistContext` trong service giữ hợp đồng principal với xác thực chung. Việc đổi cấu trúc package không đổi URL, trường JSON, chính sách nghiệp vụ hay schema.
Các operation ghi dùng Spring transaction; lỗi validation hoặc lỗi DB rollback toàn bộ.

## Chuẩn bị và chạy

Yêu cầu JDK 17; Maven Wrapper đã kèm theo. Chạy PostgreSQL theo `Hotel_Database/docker-compose.yml`, hoặc dùng PostgreSQL riêng.
Trên database mới, áp dụng lần lượt bằng `psql -v ON_ERROR_STOP=1`:

```text
Hotel_Database/sql/01_schema.sql
Hotel_Database/sql/02_operations.sql
Hotel_Database/sql/03_seed.sql
Hotel_Database/sql/05_checkin_checkout.sql
Hotel_Database/sql/06_stay_contracts.sql
```

Trên database đã cài schema v1, chạy lần lượt `05_checkin_checkout.sql` và `06_stay_contracts.sql`. Cả hai migration repeatable. Không chạy lại `01_schema.sql` hoặc ghi đè schema trên dữ liệu có sẵn. Migration 06 giữ FK phòng vật lý/loại phòng, tách FK detail đã đặt để cho phép nâng hạng/đổi loại; cập nhật `available_count` để tính cả điều chuyển tồn phòng mà không sửa giá booking.

Trong PowerShell, từ `Hotel_Backend`:

```powershell
$env:HOTEL_DB_URL='jdbc:postgresql://localhost:5433/hotel_db'
$env:HOTEL_DB_USER='hotel_admin'
$env:HOTEL_DB_PASSWORD='<mật khẩu PostgreSQL của môi trường>'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

Ứng dụng không tự chạy migration. `hotel_admin` chỉ dùng phát triển; tài khoản backend triển khai cần quyền tối thiểu cho các bảng module sử dụng và không được cung cấp thông tin đăng nhập DB cho trình duyệt.

## Authentication / authorization — điểm tích hợp bắt buộc

Module không tạo hệ thống đăng nhập mới. `ReceptionistContext` cần `HttpServletRequest.getUserPrincipal()` do filter xác thực/session/JWT chung đã kiểm chứng thiết lập, với `principal.name` là email của `hotel.users`.

- Chưa có principal: HTTP 401. Một bản chạy độc lập chưa tích hợp auth sẽ từ chối các endpoint này.
- Tra vai trò/chi nhánh từ DB; tài khoản/chi nhánh phải hoạt động, chưa xóa mềm, tài khoản không bị khóa.
- Chỉ `RECEPTIONIST` đúng `branch_id` được thao tác, kể cả xem và xuất hóa đơn.
- Không nhận user/role/branch từ body hoặc header `X-User-*`; không có tài khoản demo hay cơ chế bypass auth.
- Nếu auth chung dùng cookie/session, tầng đó cũng phải cung cấp CSRF protection cho POST. Không mở CORS hoặc tự tin các header từ reverse proxy.

## Quy tắc nghiệp vụ và các boundary

**Check-in:** trạng thái bắt buộc CONFIRMED, đúng ngày theo `branches.timezone`, giấy tờ 4–80 ký tự chữ/số/ký tự phân cách cho phép. Đây là validation định dạng; lễ tân phải kiểm tra giấy tờ gốc, không phải dịch vụ xác minh giấy tờ chính phủ. Không đưa giấy tờ vào audit log, response hay hóa đơn.

Schema chưa có trường payment plan/required deposit riêng. Theo `make_reservation` hiện tại:

- `hold_expires_at IS NULL`: trả tại khách sạn, không bắt buộc đã thu tiền trước check-in.
- `hold_expires_at IS NOT NULL`: hình thức trả trước; yêu cầu tổng PAID trừ refund DONE >= 30% `quoted_total` (làm tròn 2 chữ số), đúng lựa chọn cọc trong UC07.

Chính sách này nằm trong `CheckInValidator`; khi module đặt phòng cung cấp snapshot nghĩa vụ thanh toán chính thức, thay boundary này. Dữ liệu booking được tạo thủ công cần tuân thủ quy ước trên.

`POST /checkin/identity` xác nhận giấy tờ và lưu `stay_guests` theo booking; chưa gán phòng/chuyển trạng thái. Người ở chính có thể khác người đặt. Chỉ khi gửi `customerId` trùng người đặt mới cập nhật thêm giấy tờ ở Customer; mặc định không ghi đè Customer. `POST /checkin` dùng hồ sơ đã xác nhận, hoặc nhận `guest` đầy đủ để hoàn tất cả hai bước trong một transaction như biểu đồ trình tự UC16.

Một request gán đủ phòng cho **tất cả** `reservation_details`, đúng quantity, không trùng; chưa mở rộng partial check-in/checkout do báo cáo chưa chốt vòng đời riêng từng suất phòng. Phòng phải hoạt động, chưa xóa, loại phòng hoạt động, VACANT + READY, không có assignment active và không bảo trì chồng kỳ ở. Danh sách hỗ trợ lọc `floor`, `viewDirection`.

Nhánh nâng hạng miễn phí UC16 dùng `selections` ánh xạ rõ detail → phòng vật lý. Chỉ cho phép bù số phòng còn thiếu sẵn sàng của loại đã đặt, loại mới có `upgrade_rank` lớn hơn và capacity không thấp hơn; không suy hạng từ tên hoặc giá. `upgrade_rank` mặc định NULL, cần cấu hình thứ tự theo danh mục khách sạn. Kiểm tra tồn loại mới cho toàn kỳ và ghi điều chuyển; tiền phòng booking giữ nguyên.

**Đổi phòng UC17:** xem danh sách/chênh lệch theo `oldRoomId` và lý do `GUEST_REQUEST`, `ROOM_ISSUE`, `UPGRADE`. Phòng mới phải cùng chi nhánh, đủ tổng sức chứa của booking sau khi đổi, sẵn sàng và còn tồn cho mọi đêm còn lại. Confirm gửi lại `expectedAdjustment`; giá đổi thì yêu cầu xem lại. Cùng loại giữ giá; khác loại so giá hiện hành phòng mới với snapshot đang áp dụng cho từng đêm còn lại của assignment cũ. `ROOM_ISSUE` không tăng giá từng đêm, vẫn giảm khi giá mới thấp hơn. Kết thúc assignment cũ, mở assignment mới, phòng cũ VACANT + DIRTY, phòng mới OCCUPIED; giữ actual_checkin. Chênh lệch vào `extra_charges`, không tạo ServiceUsage giả. Mọi ghi nhận cùng transaction và giữ lịch sử.

**Check-out:** lấy dòng tiền phòng từng ngày từ `reservation_nights`, đối chiếu tổng với `room_subtotal`; thiếu/lệch snapshot thì báo lỗi thay vì âm thầm dùng giá hiện tại. Dịch vụ dùng `service_usages.unit_price`, cộng `extra_charges`, trừ discount đã chốt. Bỏ qua service usage bị void. `paidAmount` là tổng PAID; `refundedAmount` là tổng DONE; số dư = gross − paid + refunded. `refundAmount = max(0, −remainingAmount)`. Trả sớm không tự giảm các đêm chưa ở.

Thuế: cấu hình `branches.tax_rate` dạng tỷ lệ (0.08 nghĩa là 8%), snapshot vào `reservations.stay_tax_rate` khi check-in. Mặc định 0 để giữ chính sách demo vì tài liệu database chưa xác nhận VAT thực tế. Theo hợp đồng SQL nền hiện có, thuế ngoài giá = tổng sau giảm × tỷ lệ, làm tròn HALF_UP 2 số; preview, payment, refund và invoice dùng cùng phép tính. Đây chưa phải mô hình nhiều thuế suất theo dịch vụ hoặc giá đã gồm thuế. Khi đổi chính sách thực tế phải cập nhật chính sách tính tương ứng, không chỉ đổi tỷ lệ.

Phí trả muộn: `branches.late_checkout_fee` là tham số phí cố định khi quá **ngày trả dự kiến + checkout_time** theo timezone chi nhánh. NULL nghĩa là chưa có chính sách tự tính, không tự đặt mức phí. Phí là dòng tạm tính, không ghi thêm mỗi lần preview; chỉ chụp vào invoice lúc đóng. Không thu phí muộn chỉ vì khách trả sớm vào sau 12:00. Phụ phí tùy biến/miễn giảm do module ngoài ghi vào `extra_charges` cần tránh ghi trùng phí tự động đã cấu hình. Tổng âm bị từ chối.

- Preview chỉ đọc, dùng snapshot REPEATABLE READ; không giữ chỗ hay đảm bảo số dư không đổi sau khi preview.
- Khoản thu tại quầy: CASH / MOCK_QR / MOCK_CARD, ghi nhận mô phỏng; không giao dịch tiền thật.
- `obligationKey` là khóa nghĩa vụ toàn hệ thống, dùng chung UC08/UC15/UC19, không sinh khóa khác khi retry một khoản thu. Khóa đã PAID với cùng đơn/số tiền/phương thức trả lại kết quả, không ghi lần hai. Bản ghi UNPAID/PENDING tương ứng được cập nhật thay vì chèn trùng. Khóa trùng nhưng dữ liệu khác bị từ chối; khoản thu vượt số dư rollback.
- Trả dư: `POST /checkout/refunds` nhận payment gốc, số tiền, phương thức khớp khoản thu, lý do và khóa idempotency. CASH tạo PENDING; lễ tân xác nhận đã trả bằng endpoint confirm-cash mới chuyển DONE và ghi người/thời điểm. MOCK_QR/MOCK_CARD chuyển DONE ngay trong mô phỏng. Kiểm tra cả hạn mức payment lẫn booking; PENDING giữ hạn mức nhưng chưa được tính là tiền đã hoàn. Retry cùng dữ liệu không ghi lại, kể cả sau checkout; khóa khác dữ liệu bị từ chối. Khóa refund dùng chung namespace với UC11 và writer tương lai.
- Chỉ chốt khi số dư chính xác bằng 0 (`BigDecimal`), không còn refund PENDING và có assignment active. Không cho callback hoàn tiền phát sinh sau khi đóng trong phạm vi hiện tại.
- Tạo invoice hoặc đóng DRAFT hiện có, giữ mã draft và dựng lại toàn bộ dòng từ nguồn chi phí. DRAFT ở module này là bản tạm tính, không phải nguồn điều chỉnh độc lập: điều chỉnh phải vào service usages/extras trước khi chốt. Invoice CLOSED/lines và tài chính của đơn đã đóng bất biến. Mã hóa đơn duy nhất dùng UUID vì báo cáo không quy định format kế toán.
- Sau khi chốt, ghi duy nhất một `invoice_delivery_requests` trong transaction để tầng notification gửi email; export HTML có thể in ngay. Chưa có worker/SMTP gửi email thật trong repository; không báo gửi email thành công chỉ vì đã tạo yêu cầu.
- CHECKED_OUT + actual_checkout; kết thúc assignment bằng `released_at`/`departure_at`, không xóa lịch sử; phòng thành VACANT + DIRTY. Không triển khai dọn phòng.
- Retry confirm check-out trả cùng invoice, không tạo thêm hóa đơn. Retry check-in báo xung đột trạng thái.

## Transaction và concurrency

Check-in khóa reservation (`FOR UPDATE`), khóa advisory `(7101, room_type_id)` theo thứ tự tăng như SQL đặt phòng/bảo trì, rồi khóa phòng theo ID tăng. Sau khi chờ khóa, truy vấn lại điều kiện phòng bằng statement mới. Unique index `uq_room_current_assignment` là lớp bảo vệ bổ sung. Phòng chỉ được gán sau khi toàn bộ lựa chọn hợp lệ.

Check-out/thu bổ sung khóa reservation. Migration bắt buộc khóa cùng reservation khi có ghi service usages, extras, payments, refunds; kiểm tra lại trạng thái sau khi chờ. Vì vậy dịch vụ hoặc tiền không thể được ghi thêm sau lúc chốt mà lọt khỏi hóa đơn. Invoice/assignment/room/status được ghi trong cùng transaction. Các writer tương lai cũng phải giữ thứ tự khóa nhất quán; lỗi deadlock/unique/concurrency trả HTTP 409 để tải lại.

Không gọi trực tiếp các hàm SQL legacy từ trình duyệt: chúng không có authentication và không thay thế validation đầy đủ của API mới.

## API

Các URL dưới đây có tiền tố `/api/reservations/{id}`. ID là bigint của DB.

| Method | URL | Kết quả |
|---|---|---|
| GET | `/checkin/available-rooms` | Phòng vật lý đủ điều kiện, có `roomId`, `roomNumber`, `roomTypeId` |
| POST | `/checkin/identity` | Xác nhận người ở, trả danh sách phòng; chưa check-in |
| POST | `/checkin` | Gán đủ phòng, xác nhận giấy tờ và nhận phòng |
| GET | `/room-change/available-rooms?oldRoomId=1&reason=ROOM_ISSUE` | Phòng mới và chênh lệch cho các đêm còn lại |
| POST | `/room-change` | Đổi phòng và ghi điều chỉnh, lịch sử |
| GET | `/checkout/preview` | Các khoản chi phí, tổng đã thu/hoàn và số dư |
| POST | `/checkout/payment` | Ghi thu bổ sung hoặc xử lý retry cùng nghĩa vụ |
| POST | `/checkout/refunds` | Tạo khoản hoàn chênh lệch, CASH chờ xác nhận |
| POST | `/checkout/refunds/{refundId}/confirm-cash` | Xác nhận đã trả tiền mặt, chuyển DONE |
| POST | `/checkout` | Chốt hóa đơn và trả phòng; không cần body |
| GET | `/checkout/invoice` | Hóa đơn CLOSED và các dòng đã chốt |
| GET | `/checkout/invoice/export` | Tải hóa đơn HTML UTF-8 để mở/in |

Check-in một phòng (với nhiều phòng, truyền đủ ID):

```json
{"roomIds":[1],"guest":{"fullName":"Nguyễn Văn An","identificationInfo":"AB123456"}}
```

Thu bổ sung:

```json
{"amount":200000,"method":"CASH","obligationKey":"COUNTER-OBLIGATION-0001"}
```

Nhận phòng hai bước: gửi `guest` ở trên làm body `/checkin/identity`, sau đó `/checkin` chỉ cần `{"roomIds":[1]}`. Nâng hạng dùng `{"selections":[{"detailId":1,"roomId":4}]}` sau bước identity. API cũ chỉ gửi `identificationInfo` ở cấp ngoài không còn đủ dữ liệu: cần xác định đúng người ở chính.

Hoàn tiền: `{"paymentId":1,"amount":50000,"method":"CASH","idempotencyKey":"REFUND-COUNTER-0001","reason":"Trả dư"}`; xác nhận tiền mặt: `{"cashReturned":true}`. Đây là ghi nhận thao tác thực tế/mô phỏng, không chi tiền qua ngân hàng.

Đổi phòng: `{"oldRoomId":1,"newRoomId":4,"reason":"ROOM_ISSUE","expectedAdjustment":0}`.

Lỗi có dạng `{"code":"ROOM_UNAVAILABLE","message":"..."}`; 400 dữ liệu sai, 401 chưa xác thực, 403 sai quyền/chi nhánh, 404 không tìm thấy, 409 trạng thái hoặc dữ liệu xung đột.

## Kiểm thử

Unit tests chạy bằng `.\mvnw.cmd test`. Integration tests cần PostgreSQL thật và chỉ chạy khi URL chỉ đúng DB test tên `hotel_lifecycle_test`, không có query string. Suite **tạo lại schema hotel và xóa dữ liệu trong DB test này**; không trỏ vào database làm việc.

Nếu IDE đang biên dịch vào `target`, có thể dùng `.\mvnw.cmd '-Dhotel.build.directory=.local/maven-target' test` để tách output Maven khỏi IDE.

```powershell
createdb -h 127.0.0.1 -p 55439 -U hotel_test hotel_lifecycle_test
$env:HOTEL_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/hotel_lifecycle_test'
$env:HOTEL_TEST_DB_USER='hotel_test'
.\mvnw.cmd test
```

Database test dùng tài khoản cục bộ không mật khẩu hoặc cấu hình test riêng. Không có biến `HOTEL_TEST_DB_URL` thì integration suite bị skip rõ ràng, không được coi là đã test DB.

Bao gồm kiểm tra snapshot giá, số dư/refund, thu trùng và bản ghi pending dùng chung, điều kiện check-in, sai quyền/chi nhánh, request principal, export, rollback sau lỗi DB, hóa đơn nháp/đã đóng, tương thích SQL legacy và hai cuộc đua bằng transaction thật (cùng phòng; ghi dịch vụ trong khi check-out).
