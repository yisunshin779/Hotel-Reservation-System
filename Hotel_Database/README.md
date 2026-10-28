# HOTEL DATABASE | BỘ BÀN GIAO v1.0

**Đề tài:** Quản lý đặt phòng và sử dụng dịch vụ khách sạn, nhóm Sứ Thanh Hóa (IT3120).
**DBMS:** PostgreSQL 15+, thiết kế cho 3 chi nhánh và phòng đặt theo loại.

## Cấu trúc
- `sql/01_schema.sql`: 26 bảng, kiểu dữ liệu, PK/FK/CHECK/UNIQUE, chỉ mục.
- `sql/02_operations.sql`: stored functions kiểm tra tồn phòng theo từng đêm, tạo đơn, thanh toán giả lập, hủy, timeout, bảo trì, check-in, dịch vụ, tính folio, check-out.
- `sql/03_seed.sql`: 3 chi nhánh, loại phòng, phòng, dịch vụ, khách thử và mã khuyến mãi.
- `sql/04_reports.sql`: truy vấn tham khảo.
- `sql/05_checkin_checkout.sql`: migration bảo vệ tài chính và hóa đơn cho UC16/UC19; chạy sau schema/operations (và seed nếu tạo DB mới). Xem `../Hotel_Backend/README.md` để chạy API và integration tests.
- `sql/06_stay_contracts.sql`: migration theo báo cáo OC16/17/19 và nhánh hoàn tiền OC11; hồ sơ người ở, đổi/nâng hạng phòng, snapshot giá, điều chuyển tồn phòng, cấu hình thuế/phí và yêu cầu gửi hóa đơn. Chạy sau 05.
- `tests/`: smoke/negative tests và hướng dẫn race-condition 2 session.
- `diagrams/`: ERD tổng thể (PNG/SVG/DOT).
- `docs/`: data dictionary, design decisions, traceability, checklist.

## Chạy thử với Docker
```bash
docker compose up -d
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/01_schema.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/02_operations.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/03_seed.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/05_checkin_checkout.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/06_stay_contracts.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /tests/01_acceptance.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /tests/02_negative_cases.sql
```
Dùng lại `01_schema.sql` trên database đã tạo sẽ lỗi vì không phải migration; để chạy lại từ đầu, tạo DB mới hoặc `docker compose down -v` **sẽ xóa toàn bộ dữ liệu**.

## Bảo mật và giới hạn triển khai
- Các function bảo vệ kiểm tra tồn phòng chỉ an toàn khi tài khoản ứng dụng **không được trực tiếp** ghi vào bảng booking, room maintenance, room assignment. Thiết lập role/grant trong môi trường triển khai, không dùng `hotel_admin` làm tài khoản ứng dụng.
- Payment là mô phỏng, không có giao dịch ngân hàng thật. `MOCK_QR` chỉ xác nhận trong hệ thống.
- Backend đã có UC16/17/19 và nhánh hoàn chênh lệch tại checkout; các luồng thay đổi booking, hoàn do hủy/no-show, quản lý buồng phòng, xác thực tài khoản và chỉnh hóa đơn Admin chưa triển khai đầy đủ. Cấu hình thuế/phí không thay thế việc chốt chính sách thực tế.
- Báo cáo doanh thu dựa vào hóa đơn đã chốt; quy ước doanh thu, VAT phải được thống nhất trước triển khai thực tế.
- Backend có integration suite chạy trên PostgreSQL riêng, tạo lại schema chỉ trong DB test `hotel_lifecycle_test`; xem README backend. Các script SQL acceptance/negative là kiểm thử bổ sung, không thay thế test API.
