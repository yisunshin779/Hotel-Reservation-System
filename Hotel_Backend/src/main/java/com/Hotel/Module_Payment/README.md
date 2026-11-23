# Module Payment theo UC08

Nguồn yêu cầu là báo cáo `Sứ Thanh Hóa - 172889 - Báo cáo BTL Phân tích thiết kế hệ thống.docx` ở thư mục cha repository, tài liệu `Hotel_Database/docs/Thiet_ke_CSDL_Khach_san.docx` và schema SQL bàn giao. Đã đối chiếu nội dung UC08, OC-08.01–03, biểu đồ lớp/trình tự/trạng thái UC08; OC-15.04 và OC-19.02 là hợp đồng dùng chung khoản thu. Không triển khai kết nối ngân hàng thật.

## Truy vết các quyết định

| Nguồn trong báo cáo | Hành vi trong code |
|---|---|
| UC07 bước 7 | Toàn bộ hoặc cọc 30% của `quoted_total`; tính trên server bằng BigDecimal, làm tròn HALF_UP 2 số như nghiệp vụ lưu trú hiện có |
| UC08; OC-08.01; trình tự `selectMethod` | Chọn QR tạo/tái sử dụng Payment PENDING, đơn còn PENDING_PAYMENT; không tự ghi đã thu |
| OC-08.01; máy trạng thái tiền mặt | Chọn CASH tạo Payment UNPAID, xác nhận Reservation, không tăng tổng đã thu; bỏ hạn giữ để giữ đúng dấu hiệu trả tại khách sạn của CheckInValidator |
| OC-08.02; `confirmQRAtomically` | Khách phải xác nhận riêng sau khi chọn QR; khóa reservation rồi kiểm tra lại trạng thái, hạn giữ và lựa chọn còn hiệu lực; cập nhật đúng Payment và xác nhận đơn trong cùng transaction |
| UC08 nhánh 3a; trạng thái QR Hủy | Hủy QR không hủy đơn và không thu tiền; đổi lựa chọn vô hiệu QR cũ; hết hạn không được xác nhận |
| OC-08.03 | Chỉ lễ tân đúng chi nhánh được xác nhận thực thu CASH; cập nhật Payment UNPAID đang tồn tại, lưu recorded_by/paid_at; không cho khách tự gạch nợ |
| OC-15.04; OC-19.02 | Dùng chung `payments.obligation_key` và writer `StayRepository.recordPayment`; nếu thu lúc CHECKED_IN, gọi CheckOutService.payment để kiểm tra số dư folio; không tạo bản ghi thứ hai cho cùng nghĩa vụ |
| UC08 Sequence, nhánh already PAID | Retry trả kết quả cũ, kể cả sau checkout, không viết lại bản ghi tài chính đã đóng |
| UC07/08: quá 15 phút | Gọi routine `hotel.expire_holds()` đã có qua scheduled job; routine hủy đơn, giải phóng tồn và hoàn lượt khuyến mãi. API kiểm tra đồng hồ DB sau khi lấy khóa, không phụ thuộc lần quét gần nhất |
| UC08 Sequence: Notification sau COMMIT | Ghi một yêu cầu xác nhận đặt phòng trong transactional outbox; chỉ nhìn thấy sau commit. Chưa có worker email/SMS nên PENDING không có nghĩa đã gửi |
| Phạm vi mô phỏng | Chỉ CASH/QR trong UC08; không lưu thẻ, tài khoản ngân hàng; QR là hình hiển thị mô phỏng, không phải mã chuyển khoản thật |

`payment_intents` là chi tiết triển khai để nhận diện lựa chọn còn hiệu lực, chống xác nhận QR cũ và giữ snapshot số tiền/plan. Payment và Reservation vẫn có vòng đời riêng như biểu đồ. Khóa nghĩa vụ do server sinh, dùng lại khi retry; API không lấy số tiền hoặc ID khách từ request. Cọc 30% và phần còn lại được đối soát bằng các bản ghi PAID/refund DONE hiện có, không sửa giá phòng snapshot.

## Cấu trúc và API

`entity` giữ model; `dto` giữ request/response; `repository` truy cập PostgreSQL; `service` thực hiện transaction và phân quyền; `controller` cung cấp API; `security` lấy principal HTTP Basic từ `hotel.users`.

Các URL dưới đây có tiền tố `/api/reservations/{id}/payments`. `{id}` là bigint trong `hotel.reservations`, `{intent}` là UUID lựa chọn trả về từ POST.

| Method | URL | Hành vi |
|---|---|---|
| GET | `/` | Tiền phòng, cọc, số đã thu, hạn giữ, lựa chọn hiện tại; chủ đơn hoặc lễ tân đúng chi nhánh được đọc |
| POST | `/` | Chọn `{"method":"QR","plan":"DEPOSIT"}`; method QR/CASH, plan FULL/DEPOSIT |
| POST | `/{intent}/confirm-qr` | Xác nhận “Tôi đã thanh toán”; không cần body |
| POST | `/{intent}/cancel` | Hủy lựa chọn QR chưa thu; không cần body |
| POST | `/{intent}/collect-cash` | Lễ tân xác nhận `{"cashReceived":true}` cho đúng khoản đã chọn |

Ví dụ: chọn FULL/CASH xác nhận đơn nhưng response vẫn có `intent.paymentStatus=UNPAID`, `paidAmount=0`. Chỉ collect-cash hoặc writer thu tại checkout với cùng `obligationKey` mới chuyển khoản đó sang PAID. `remainingAmount` của API này là phần tiền phòng còn lại, không thay folio tính dịch vụ/thuế/phụ phí của checkout.

HTTP 400: request sai; 401: chưa xác thực; 403: sai chủ đơn/quyền/chi nhánh; 404: không có đơn/lựa chọn; 409: quá hạn, sai trạng thái, QR cũ, giá đổi hoặc xung đột ghi. Lỗi trả `{code,message}`. Số tiền gửi thêm trong JSON không được sử dụng để tính khoản thu.

## Cài đặt

Schema Payment được tạo bởi Flyway V8 trên DB mới. Với DB cũ, đối chiếu cấu trúc qua SQL 08 trước khi baseline. Xem [hướng dẫn backend](../../../../../../README.md) để chọn đúng quy trình.

Scheduled job mặc định quét mỗi 60 giây. Cấu hình `hotel.payment.expiry-enabled=false` để tắt khi một tiến trình khác chịu trách nhiệm quét; `hotel.payment.expiry-delay-ms` điều chỉnh chu kỳ. Kiểm tra hạn trong request vẫn hoạt động khi job tắt. Job dùng `FOR UPDATE SKIP LOCKED` trong routine bàn giao.

API dùng HTTP Basic với `hotel.users` như cơ chế staff/report đang có; principal khách phải liên kết qua `customers.user_id` của chính reservation. Role/branch/customer không đọc từ header tự khai báo. Các endpoint payment có filter riêng trước filter receptionist của lưu trú; các API lưu trú khác vẫn giữ nguyên quyền.

Phạm vi triển khai chỉ gồm backend, migration và kiểm thử API. Frontend giữ nguyên trạng thái trước khi làm module payment; không bổ sung giao diện, API client hoặc test frontend. Giao diện hiện có vẫn dùng dữ liệu mock và chưa kết nối với các endpoint payment này.

Payment nhận bigint từ `hotel.reservations`. Booking API tiếp tục ghi UUID vào `hotel.reservation`, nên luồng từ Booking API sang Payment còn chờ ánh xạ đơn và chủ đơn. Đã kiểm thử từ `hotel.make_reservation` qua Payment, check-in, check-out đến hóa đơn đóng. Outbox xác nhận đặt phòng đang chờ worker gửi.

## Kiểm thử

Từ `Hotel_Backend`: `.\mvnw.cmd '-Dtest=PaymentIntegrationTest' test`. Suite tạo PostgreSQL nhúng riêng, áp schema/operations/migration thật (chạy 07 hai lần để kiểm tra repeatable); không truy cập DB làm việc. Redis và BookingService legacy được mock để cô lập hạ tầng không thuộc payment. Bao gồm HTTP Basic, chủ đơn/chi nhánh, QR hai bước/hủy/đổi, cọc, retry, đồng thời, hết hạn dưới khóa, rollback outbox, ghi nhận tiền mặt và dùng lại khoản thu qua checkout.
