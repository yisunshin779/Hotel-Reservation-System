# Truy vết triển khai lưu trú theo báo cáo dự án

Nguồn ưu tiên: báo cáo `Sứ Thanh Hóa - 172889 - Báo cáo BTL Phân tích thiết kế hệ thống.docx` ở thư mục cha repository. Đã đọc phần UC và Operation Contracts, đối chiếu cả hình biểu đồ lớp/trình tự UC16/17/19. Không dùng các nhận xét AI trước đó làm yêu cầu mới.

| Nguồn trong báo cáo | Hành vi triển khai |
|---|---|
| OC-16.01 | Hồ sơ người ở chính riêng theo reservation, không mặc định trùng người đặt; xác nhận giấy tờ trước khi gán phòng |
| OC-16.02; trình tự UC16 | Kiểm tra lại ngày/trạng thái/cọc/phòng dưới khóa; hỗ trợ gộp identity và assign thành một transaction; không lưu giấy tờ vào log/response |
| UC16 nhánh 4a | Nâng hạng miễn phí khi thiếu phòng sẵn sàng, dùng thứ tự hạng được cấu hình và tồn còn đủ cả kỳ; không tăng giá booking |
| UC16; biểu đồ lớp | Lọc phòng theo tầng, hướng nhìn; phòng chỉ được chọn khi VACANT + READY và không bảo trì/assignment active |
| UC17; OC-17.01 | Lý do GUEST_REQUEST/ROOM_ISSUE/UPGRADE, kiểm tra sức chứa và tồn đêm còn lại; đóng/mở assignment nguyên tử, giữ lịch sử và giờ check-in gốc |
| UC17 nhánh 4a | Sự cố khách sạn: miễn tăng giá khi nâng hạng, giảm khi giá mới thấp hơn; các đêm đã ở không tính lại |
| OC-19.01 | Dòng tiền phòng theo snapshot từng đêm, dịch vụ theo unit_price snapshot, không tự hoàn đêm chưa ở |
| OC-19.02; OC-11.01/02 | Hoàn chênh lệch với payment gốc, hạn mức, idempotency; CASH PENDING rồi xác nhận DONE, QR/thẻ giả lập DONE |
| OC-19.03 | Số dư 0 và không còn khoản hoàn chờ; đóng invoice, trả toàn booking, phòng VACANT + DIRTY; hóa đơn/financial data đã đóng bất biến |
| OC-19.03; trình tự UC19 | Tạo yêu cầu gửi hóa đơn sau khi transaction thành công thông qua bảng delivery requests; có bản xuất HTML để in |
| Phạm vi billing; tài liệu DB mục giới hạn | Có tax policy cấu hình, không tự gán VAT thực tế khi tài liệu chưa xác nhận |

Các lựa chọn triển khai cần phân biệt với yêu cầu đã chốt:

- Báo cáo không quy định số tuần tự hay format invoice: giữ UUID UNIQUE, giữ số của draft hiện có.
- Chưa có chính sách partial check-in/checkout đầy đủ; giữ nhận/trả toàn booking. Nhiều phòng cùng loại vẫn là một detail có quantity, nhiều assignment.
- Không thêm room_assignment_id bắt buộc vào service_usages: báo cáo chốt service gắn vào Reservation, chọn số phòng là cách tra booking; không yêu cầu bill riêng từng phòng.
- Hạng phòng miễn phí không suy ra từ tên/giá: thêm upgrade_rank nullable để cấu hình thứ tự. Chưa cấu hình thì không tự nâng hạng.
- Điều chuyển tồn loại phòng được lưu riêng, available_count dùng cùng dữ liệu với make_reservation/maintenance_block; reservation_nights và room_subtotal giữ nguyên snapshot tiền. Chênh lệch giá vào extra_charges.
- Thuế ngoài giá trên tổng sau giảm, làm tròn 2 số, theo nền tảng folio_preview hiện có. Snapshot tỷ lệ khi check-in; mặc định 0. Thuế suất thực tế, nhiều thuế suất và mô hình giá đã gồm thuế chưa được tài liệu xác nhận.
- Phí trả muộn chưa có công thức/mức cụ thể trong báo cáo. Tham số late_checkout_fee cho phép cấu hình mức cố định của chi nhánh, mặc định NULL (không tự áp phí). Khi khách sạn chốt chính sách theo giờ/phần trăm cần thay policy tương ứng.
- DRAFT được tính lại từ các bảng chi phí nguồn. Không nhận điều chỉnh chỉ tồn tại trong invoice_lines. Điều chỉnh sau đóng thuộc Admin, chưa triển khai.
- Giữ tích hợp principal đã xác thực của UC02; không triển khai lại hệ thống tài khoản trong module lưu trú. API chạy độc lập chưa có adapter xác thực vẫn trả 401.
- Delivery request là yêu cầu gửi; chưa có notification worker/SMTP nên không coi là email đã gửi. Thanh toán/hoàn QR/thẻ vẫn mô phỏng, đúng phạm vi báo cáo.

Migration 06 mở rộng schema nền một cách repeatable, không sửa báo cáo DOCX và không tự sửa dữ liệu tài chính đã đóng. Chạy migration trên database đích trước khi dùng API mới.
