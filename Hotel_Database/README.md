# HOTEL DATABASE | BỘ BÀN GIAO v1.0

**Đề tài:** Quản lý đặt phòng và sử dụng dịch vụ khách sạn, nhóm Sứ Thanh Hóa (IT3120).
**DBMS:** PostgreSQL 15+, thiết kế cho 3 chi nhánh và phòng đặt theo loại.

## Cấu trúc
- `sql/01_schema.sql`: 26 bảng, kiểu dữ liệu, PK/FK/CHECK/UNIQUE, chỉ mục.
- `sql/02_operations.sql`: stored functions kiểm tra tồn phòng theo từng đêm, tạo đơn, thanh toán giả lập, hủy, timeout, bảo trì, check-in, dịch vụ, tính folio, check-out.
- `sql/03_seed.sql`: 3 chi nhánh, loại phòng, phòng, dịch vụ, khách thử và mã khuyến mãi.
- `sql/04_reports.sql`: truy vấn tham khảo.
- `tests/`: smoke/negative tests và hướng dẫn race-condition 2 session.
- `diagrams/`: ERD tổng thể (PNG/SVG/DOT).
- `docs/`: data dictionary, design decisions, traceability, checklist.

## Chạy thử với Docker
```bash
docker compose up -d
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/01_schema.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/02_operations.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /sql/03_seed.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /tests/01_acceptance.sql
docker compose exec -T postgres psql -U hotel_admin -d hotel_db -v ON_ERROR_STOP=1 -f /tests/02_negative_cases.sql
```
Dùng lại `01_schema.sql` trên database đã tạo sẽ lỗi vì không phải migration; để chạy lại từ đầu, tạo DB mới hoặc `docker compose down -v` **sẽ xóa toàn bộ dữ liệu**.

## Bảo mật và giới hạn triển khai
- Các function bảo vệ kiểm tra tồn phòng chỉ an toàn khi tài khoản ứng dụng **không được trực tiếp** ghi vào bảng booking, room maintenance, room assignment. Thiết lập role/grant trong môi trường triển khai, không dùng `hotel_admin` làm tài khoản ứng dụng.
- Payment là mô phỏng, không có giao dịch ngân hàng thật. `MOCK_QR` chỉ xác nhận trong hệ thống.
- Chưa triển khai đầy đủ toàn bộ UC: thay đổi booking, đổi phòng, xử lý hoàn tiền, no-show, quản lý trạng thái buồng phòng, xác thực email, RBAC API và thuế thực tế đòi hỏi service/application riêng. Có bảng để lưu nhưng không được xem là đã hoàn thiện chức năng.
- Báo cáo doanh thu dựa vào hóa đơn đã chốt; quy ước doanh thu, VAT phải được thống nhất trước triển khai thực tế.
- Chưa có Postgres server sẵn trong môi trường tạo file, do đó chưa chứng thực test tích hợp đã PASS. Script kiểm thử được cung cấp để thực thi ở máy có Docker.
