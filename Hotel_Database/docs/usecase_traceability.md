# Truy vết Use Case → cơ sở dữ liệu

| Use Case | Bảng | Trạng thái |
|---|---|---|
| UC01–04 | users, roles, customers, auth_tokens | Lược đồ lưu dữ liệu; authentication/email ở tầng ứng dụng |
| UC05–07 | room_types, rooms, reservations, reservation_details, reservation_nights, pricing_rules, promotions | make_reservation, available_count, nightly_rate |
| UC08 | payments | confirm_mock_qr |
| UC09–10 | reservations, reservation_details, payments | cancel_reservation; chức năng sửa booking chưa có stored procedure |
| UC11 | refunds, payments | Bảng lưu; nghiệp vụ refund chưa triển khai |
| UC12–13 | reviews, support_requests, support_replies | Bảng lưu; nghiệp vụ kiểm quyền ở tầng ứng dụng |
| UC14–15 | customers, reservations, payments | make_reservation với WALK_IN; ghi thu khác qua tầng ứng dụng |
| UC16 | room_assignments, rooms, reservations | checkin_assign |
| UC17 | room_assignments, extra_charges | Thiết kế bảng; chưa có routine đổi phòng |
| UC18 | services, service_usages | add_service_usage |
| UC19 | invoices, invoice_lines, payments, rooms | folio_preview, checkout_close |
| UC20 | reservations, refunds | Bảng lưu; chưa có routine no-show |
| UC21–23 | rooms, room_maintenance | maintenance_block; room cleaning transition cần API |
| UC24–26 | pricing_rules, promotions, services | Rule tables và constraints |
| UC27 | users, roles | Roles, link branch; phân quyền API cần triển khai |
| UC28–30 | invoices, reservations, audit_logs, branches | Truy vấn mẫu và bảng audit/branch |