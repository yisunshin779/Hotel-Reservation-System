# Backend hệ thống đặt phòng khách sạn

Backend dùng Java 17, Spring Boot và PostgreSQL. Xem tiến độ, ma trận API và kết quả kiểm thử trong [báo cáo rà soát 23/11/2026](docs/backend_review_2026-11-23.md).

| Phạm vi | Dữ liệu và xác thực hiện tại |
| --- | --- |
| Account và Booking | JWT. Account dùng `hotel.account`. Booking ghi đơn UUID vào `hotel.reservation`. |
| Payment và lưu trú | HTTP Basic từ `hotel.users`. Các API dùng ID bigint của `hotel.reservations`. |
| Report | HTTP Basic, quyền Admin hoặc Manager. Manager chỉ xem chi nhánh của mình. |

Việc ánh xạ đơn UUID và hợp nhất nguồn tài khoản đang chờ đối chiếu dữ liệu lịch sử. Luồng từ `hotel.make_reservation` đến hóa đơn đã được kiểm thử.

## Chạy và kiểm thử

- Dùng [mẫu biến môi trường](.env.example) để cấu hình tiến trình. Mọi lần chạy backend cần khóa JWT ngẫu nhiên ít nhất 32 byte UTF-8. Booking cần Redis.
- Với DB trống hoặc DB có dữ liệu, làm theo [hướng dẫn Flyway](docs/backend_review_2026-11-23.md#c-database-và-triển-khai). Profile production dùng Hibernate `validate`.
- Từ thư mục này, chạy `mvn test` hoặc `.\mvnw.cmd test`. Riêng `StayIntegrationTest` cần `HOTEL_TEST_DB_URL` trỏ đến PostgreSQL riêng có database tên `hotel_lifecycle_test`. Suite tạo lại schema `hotel` trong database đó. Các suite Payment, Invoice Adjustment, Report và Account/Booking dùng PostgreSQL nhúng.

## Tài liệu nghiệp vụ

- [Payment](src/main/java/com/Hotel/Module_Payment/README.md)
- [Check-in, đổi phòng, check-out và hóa đơn](src/main/java/com/Hotel/Module_CheckInCheckOut/README.md)
- [Truy vết hợp đồng lưu trú](docs/stay_contracts.md)
- [Report và hướng dẫn chạy demo](src/main/java/com/Hotel/Module_Report/README.md)

Các báo cáo DOCX ngày 14/11 và 21/11 trong `docs/` ghi lại tiến độ tại thời điểm lập báo cáo.
