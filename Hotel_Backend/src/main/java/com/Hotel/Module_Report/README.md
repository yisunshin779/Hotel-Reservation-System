# Module Report theo UC28

Module gồm doanh thu, công suất phòng, thống kê đơn và hiệu quả khuyến mãi. Đối chiếu UC28, OC-28.01 (truy xuất), OC-28.02 (Excel), OC-28.03 (so sánh chi nhánh).

## Chạy trên máy hiện tại

Cần JDK 17+, Maven, PostgreSQL và Node.js 22.12+ để chạy frontend.
Database demo là `hotel_report_demo`, PostgreSQL `127.0.0.1:5432`, user `postgres`.

Từ `Hotel_Backend`:

```powershell
# Nếu Java/Maven chưa có trong PATH, dùng bộ công cụ đã tải trong phiên làm việc:
$tools = Join-Path $env:TEMP 'hotel-report-tools'
$env:JAVA_HOME = "$tools\jdk-17.0.20.1+1"
$env:Path = "$env:JAVA_HOME\bin;$tools\apache-maven-3.9.9\bin;$env:Path"

mvn test
mvn -DskipTests package

$env:DB_PASSWORD = '<mật khẩu postgres trên máy>'
$env:REPORT_DEMO_PASSWORD = '<mật khẩu đăng nhập báo cáo demo do bạn chọn>'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-report-demo.ps1
```

Script chỉ tạo database khi chưa tồn tại, không reset DB. Sau dữ liệu cơ bản, script nạp phần mở rộng demo `06_report_demo_details.sql`, có thể chạy lại mà không nhân dữ liệu.
Nếu không đặt `REPORT_DEMO_PASSWORD`, script tạo và in một mật khẩu ngẫu nhiên. Mật khẩu báo cáo khác thông tin kết nối DB.
Profile `report-demo` tạo/cập nhật hai tài khoản BCrypt, chỉ trong database demo:

- `admin-report@example.test`: Admin toàn chuỗi.
- `manager-report@example.test`: Manager chi nhánh đầu tiên.

Mật khẩu hai tài khoản lấy từ `REPORT_DEMO_PASSWORD`, không ghi vào source. Profile mặc định không tạo tài khoản.
Ngoài demo, API dùng email, password hash và role trong `hotel.users`/`hotel.roles`.

Từ `Hotel_Frontend`, mở terminal khác:

```powershell
npm ci
npm run dev
```

Mở `http://127.0.0.1:5173/dashboard/reports`, đăng nhập bằng tài khoản trên.
Vite proxy `/api` sang backend cổng 8080. Chọn báo cáo, cách xem, ngày, chi nhánh/loại phòng rồi nhấn **Xem báo cáo**.
**Xuất Excel** luôn xuất bộ lọc của kết quả đang hiển thị, kể cả khi form đã được chỉnh nhưng chưa bấm xem lại.
Thông tin xác thực chỉ giữ trong bộ nhớ trang, không lưu localStorage.

## API và xác thực

Tất cả `/api/reports/**` yêu cầu HTTP Basic, role ADMIN hoặc MANAGER; dùng HTTPS khi triển khai ngoài máy local.
Chưa đăng nhập/sai mật khẩu/tài khoản khóa: 401. Sai vai trò hoặc vượt phạm vi chi nhánh: 403.
Manager tự giới hạn vào chi nhánh gắn với tài khoản; admin được chọn toàn chuỗi hoặc nhiều chi nhánh.
Phạm vi được kiểm tra lại trong service cho cả API đọc và export. Không tin role/userId do client gửi.
HTTP Basic là adapter xác thực riêng cho report trong lúc module đăng nhập chung chưa có; khi tích hợp auth chung cần thay adapter và giữ kiểm tra phạm vi trong service.

Base URL: `http://localhost:8080/api/reports`

| GET | Nội dung |
|---|---|
| `/options` | Vai trò hiện tại, chi nhánh và loại phòng được phép xem |
| `/revenue/summary`, `/revenue/by-period`, `/revenue/by-branch` | Tổng hợp, theo kỳ, theo chi nhánh |
| `/occupancy/summary`, `/occupancy/by-period`, `/occupancy/by-branch` | Công suất phòng |
| `/reservations/summary`, `/reservations/by-period`, `/reservations/by-branch` | Thống kê đơn |
| `/promotions` | Chi tiết hiệu quả theo từng khuyến mãi |
| `/promotions/summary`, `/promotions/by-period`, `/promotions/by-branch` | Tổng hợp khuyến mãi theo các chiều chung |
| `/export` | File `.xlsx` từ cùng service và phạm vi quyền |

Bộ lọc:

- `from`, `to`: bắt buộc, `yyyy-MM-dd`, bao gồm cả hai ngày; `to <= from.plusYears(1)`.
- `branchIds=1,2`: tùy chọn; bỏ trống để lấy toàn phạm vi được phép.
- `branchId=1`: tương thích API cũ; không gửi cùng `branchIds`.
- `roomTypeIds=1,4`: tùy chọn; phải thuộc phạm vi chi nhánh đã chọn.
- `groupBy=DAY|WEEK|MONTH`, mặc định DAY. Tuần bắt đầu thứ Hai.
- Export thêm `reportType=REVENUE|OCCUPANCY|RESERVATIONS|PROMOTIONS` và `view=summary|by-period|by-branch|promotions`.
  View `promotions` chỉ dùng cho loại PROMOTIONS.

Ví dụ Postman: chọn Authorization → Basic Auth, rồi gọi:

```text
GET /api/reports/revenue/summary?from=2026-10-01&to=2026-10-31&branchIds=1,2
GET /api/reports/occupancy/by-period?from=2026-10-01&to=2026-10-31&groupBy=WEEK
GET /api/reports/reservations/by-branch?from=2026-10-01&to=2026-10-31
GET /api/reports/promotions?from=2026-10-01&to=2026-10-31
GET /api/reports/export?reportType=REVENUE&view=by-branch&from=2026-10-01&to=2026-10-31
```

Response chung có `reportType`, `from`, `to`, `branchIds`, `roomTypeIds`, `groupBy`, `hasData`, `summary`.
View summary trả thêm các chỉ tiêu ở cấp đầu để tương thích revenue API cũ. Các view khác trả `items`.
Mỗi item có chỉ tiêu, chiều nhóm và `hasData`. Không có dữ liệu vẫn trả 200 với `hasData=false`, tổng bằng 0;
tỷ lệ có mẫu số 0 trả null. UI hiện thông báo Không có dữ liệu.
Ngày/ngữ cảnh/ID không hợp lệ: 400. Chi nhánh không tồn tại trong phạm vi admin: 404.

## Quy ước tính toán

Đây là các quyết định triển khai ở những chỗ tài liệu UC28 chưa mô tả chi tiết.
Mọi mốc ngày dùng `Asia/Ho_Chi_Minh`, không phụ thuộc timezone máy chủ.
SQL lọc timestamp theo `[đầu ngày from, đầu ngày sau to)`; kỳ đầu/cuối chỉ lấy phần trong bộ lọc.

### Doanh thu

- `invoiceTotal`: tổng snapshot `invoices.total`, status CLOSED, theo `closed_at`.
- `refundTotal`: tổng refund DONE theo `completed_at`, kể cả hoàn cho đơn/hóa đơn kỳ trước.
- `netRevenue = invoiceTotal - refundTotal`. Kỳ chỉ có hoàn tiền vẫn có dữ liệu, doanh thu có thể âm.
- Đếm riêng `closedInvoiceCount`, `refundCount`; không join nhiều payment/refund vào hóa đơn rồi SUM.
- Trả riêng tiền phòng, dịch vụ, phụ thu, giảm giá, thuế. Không lấy quoted_total làm doanh thu.

### Lọc loại phòng cho số tiền

Hóa đơn/refund hiện chỉ gắn với đơn, không gắn dòng tiền cho từng loại phòng. Khi chọn loại phòng,
phân bổ các khoản tiền theo `tổng subtotal loại phòng được chọn / tổng subtotal chi tiết đơn`.
Nếu mọi subtotal đều 0, dùng tỷ trọng số lượng phòng. Mỗi khoản làm tròn 2 chữ số thập phân bằng PostgreSQL numeric.
Đơn khớp vẫn được đếm khi loại phòng được chọn có giá 0. Đếm hóa đơn/đơn là số thực thể khớp, không nhân tỷ trọng.
Do làm tròn, cộng các lần lọc loại phòng rời nhau có thể lệch một vài xu; đây là phân bổ quản trị, không phải hạch toán chi tiết theo loại phòng.

### Công suất

- `soldRoomNights`: tổng quantity của reservation_nights trong kỳ, chỉ đơn CONFIRMED/CHECKED_IN/CHECKED_OUT.
- Không tính pending, hủy, no-show. Đêm check-out không được tạo trong reservation_nights.
- `availableRoomNights`: tổng số phòng khả dụng từng ngày, trừ lịch bảo trì có hiệu lực trong ngày.
- Bảo trì đã đóng vẫn dùng khoảng ngày đã lưu; bản ghi inactive chưa đóng được coi là hủy lịch.
- `occupancyRate = soldRoomNights / availableRoomNights * 100`; tổng kỳ tính từ tổng đêm, không trung bình tỷ lệ từng ngày.
- Mẫu số 0 trả null; không ép tỷ lệ về 100 để che dữ liệu bất nhất.
- **Giới hạn dữ liệu lịch sử:** rooms chỉ có active hiện tại, không có lịch sử kích hoạt/ngừng hoạt động hay ngày tạo phòng.
  Công suất quá khứ vì thế dùng danh mục phòng hiện tại cùng lịch bảo trì đã lưu. Muốn khôi phục chính xác sức chứa lịch sử cần bổ sung lịch sử room inventory ở module quản lý phòng.

### Thống kê đơn

Chọn đơn theo `created_at`, đếm trạng thái hiện tại: pending, confirmed, checked-in, checked-out, cancelled, no-show.
Tỷ lệ hủy/no-show chia tổng đơn khớp bộ lọc. Bộ lọc loại phòng dùng điều kiện tồn tại chi tiết, không đếm trùng đơn nhiều loại phòng.
Đây không phải báo cáo trạng thái tại thời điểm quá khứ vì schema chưa có lịch sử chuyển trạng thái đầy đủ.

### Khuyến mãi

`usageCount`: lượt sử dụng trong kỳ theo used_at còn hiệu lực hiện tại; `reversedUsageCount`: lượt trong kỳ đã đảo.
`discountTotal`: tổng ưu đãi của lượt còn hiệu lực. Tổng hóa đơn/hoàn tiền liên quan dùng kỳ chốt hóa đơn/kỳ hoàn giống báo cáo doanh thu,
không bắt buộc lượt sử dụng cũng phải nằm trong kỳ đó. Chưa gọi là ROI vì không có dữ liệu chi phí chiến dịch.

## Excel

Dùng Apache POI, tạo 3 sheet: Report info (bộ lọc/phạm vi/đơn vị), Summary (tổng), Data (chi tiết).
Ngày là ô ngày Excel, tiền/số lượng/tỷ lệ là ô số. Dùng lại kết quả đã qua kiểm quyền; không có đường export bỏ qua phạm vi.

## Dữ liệu demo

`05_report_demo.sql`: 6 hóa đơn CLOSED trong tháng 10/2026, 1 DRAFT, 1 CLOSED tháng 9.
`06_report_demo_details.sql`: chi tiết phòng/đêm, khuyến mãi, payment, 2 khoản hoàn tổng 200.000,
3 đơn hủy/no-show/pending và một lịch bảo trì 3 đêm.
Tháng 10: invoiceTotal=21.550.000, refundTotal=200.000, netRevenue=21.350.000.
Đây là dữ liệu thử báo cáo, không phải dữ liệu kinh doanh thực tế.

## Kiểm thử

```text
mvn test
mvn package
```

ReportIntegrationTest chạy PostgreSQL tạm trên cổng ngẫu nhiên, nạp schema thật; dữ liệu từng test rollback,
không truy cập DB phát triển. Bao gồm refund khác kỳ, số tiền thập phân, bộ lọc nhiều chiều,
nhóm tuần/năm, công suất và bảo trì, trạng thái đơn, khuyến mãi, quyền 401/403, Excel đúng kiểu dữ liệu.

Frontend:

```text
npm run build
npm run lint
```

## Truy vết UC28

| Yêu cầu | Thành phần |
|---|---|
| 4 nhóm báo cáo | ReportRepository, ReportService, ReportController |
| Quyền admin/manager theo chi nhánh | ReportSecurityConfig, ReportAccessService |
| Ngày/tuần/tháng, <=1 năm, nhiều chi nhánh/loại phòng | ReportFilter, ReportGroupBy, ReportService |
| Không có dữ liệu | hasData và UI empty state |
| So sánh chi nhánh | by-branch, bảng và biểu đồ frontend |
| Excel đúng bộ lọc/phạm vi | ReportExportService, /export |

Không sửa schema SQL hoặc module nghiệp vụ khác. Adapter auth riêng và các giới hạn lịch sử/phân bổ ở trên cần được xem xét khi tích hợp toàn hệ thống.
