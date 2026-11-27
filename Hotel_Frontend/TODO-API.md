# TODO-API: Các điểm cần thống nhất với Backend

Booking, tìm phòng, phòng lễ tân và tiện ích còn dùng dữ liệu giả. Tài khoản dùng `VITE_USE_MOCK_AUTH`; đặt `false` để nối auth/profile backend, các module chưa khớp vẫn giữ mock.
Đánh dấu `[x]` khi endpoint đã được đối chiếu và tích hợp. Các API chưa có trên backend được giữ mock.
Đánh dấu `[x]` khi đã xác nhận và sửa xong.

## 0. Thông tin chung

- [x] Cổng server `http://localhost:8080`, tiền tố client `http://localhost:8080/api` (`VITE_API_BASE_URL`)
- [x] CORS cho `http://localhost:5173`
- [x] Auth module dùng JWT `Authorization: Bearer <token>`. Report module vẫn dùng HTTP Basic riêng.
- [x] Auth lỗi trả `{ status, message }`; client cũng xử lý response lỗi dạng chuỗi
- [ ] Mã lỗi: hiện giả định `401` hết phiên đăng nhập, `409` phòng vừa bị người khác đặt, `400` dữ liệu không hợp lệ
- [ ] Định dạng ngày: hiện giả định chuỗi `YYYY-MM-DD` (ngày) và ISO 8601 (thời điểm, ví dụ `holdExpiresAt`)
- [ ] Định dạng tiền: hiện giả định số nguyên VND (không có số thập phân)

## 1. Các API frontend đang gọi

Cột "Đường dẫn" là phần **giả định**, cần đối chiếu với Controller thật.

| Chức năng | Phương thức | Đường dẫn (giả định) | Gửi lên | Mong đợi trả về | Đã khớp |
|---|---|---|---|---|---|
| Đăng nhập | POST | `/v1/auth/login` | `{ email, matKhau }` | `{ status, data: { token, email, vaiTro } }` | [x] |
| Đăng ký | POST | /v1/auth/register | { hoTen, email, soDienThoai, matKhau, nhapLaiMatKhau } | 201 + xác thực email | [x] |
| Quên mật khẩu | POST | /v1/auth/forgot-password | { email } | Gửi email link token | [x] |
| Đặt mật khẩu mới | POST | /v1/auth/reset-password | { token, matKhauMoi, nhapLaiMatKhauMoi } | 200 OK | [x] |
| Xem hồ sơ | GET | /v1/accounts/me | – | { status, data: AccountProfileResponseDTO } | [x] |
| Cập nhật hồ sơ | PUT | /v1/accounts/me | { hoTen, soDienThoai, cccd } | { status, data: AccountProfileResponseDTO } | [x] |
| Danh sách chi nhánh | GET | `/branches` | – | `Branch[]` | [ ] |
| Tìm phòng trống | GET | `/rooms/availability` | query: `branchId, checkIn, checkOut, adults, children, rooms` | `Availability[]` | [ ] |
| Tạo đơn đặt phòng | POST | `/reservations` | `CreateReservation` | `Reservation` (trạng thái Pending, có `holdExpiresAt`) | [ ] |
| Xem một đơn | GET | `/reservations/{id}` | – | `Reservation` | [ ] |
| Thanh toán | POST | `/reservations/{id}/payments` | `{ method: "QR" \| "CASH" }` | 200 OK | [ ] |
| Đơn của tôi | GET | `/reservations/me` | – | `Reservation[]` | [ ] |
| Hủy đơn | POST | `/reservations/{id}/cancel` | – | 200 OK | [ ] |

Các API sẽ cần thêm ở các bước sau (điền khi làm tới):

| Chức năng | Phương thức | Đường dẫn (giả định) | Đã khớp |
|---|---|---|---|
| Sơ đồ phòng (lễ tân) | GET | `/staff/rooms` | [ ] |
| Đổi trạng thái phòng | PATCH | `/staff/rooms/{id}/status` | [ ] |
| Danh sách đơn (lễ tân, có tìm kiếm) | GET | `/staff/reservations?q=` | [ ] |
| Check-in | POST | `/staff/reservations/{id}/check-in` | [ ] |
| Check-out | POST | `/staff/reservations/{id}/check-out` | [ ] |
| Báo cáo doanh thu | GET | `/reports/revenue?days=7` | [ ] |
| Thông báo của tôi | GET | `/notifications/me` | [ ] |
| Đánh dấu đã đọc | PATCH | `/notifications/{id}/read` | [ ] |
| Đánh dấu tất cả đã đọc | PATCH | `/notifications/me/read-all` | [ ] |
| Danh sách tài khoản admin | GET | `/v1/admin/accounts` | [x] |
| Đổi vai trò tài khoản | PATCH | `/v1/admin/accounts/{id}/role` | [x] |
| Khóa/mở tài khoản | PATCH | `/v1/admin/accounts/{id}/status` | [x] |

## 2. Tên trường dữ liệu cần đối chiếu (`src/types.ts`)

Nếu backend đặt tên khác, sửa trong `types.ts` (hoặc đổi tên khi nhận dữ liệu).

- [ ] **User**: `id, fullName, email, role, branchId` — giá trị `role` là gì? (giả định `guest | receptionist | manager | admin`)
- [ ] **Branch**: `branchId, name, address`
- [ ] **RoomType**: `roomTypeId, name, capacity, basePrice, description, amenities[], images[]`
- [ ] **Availability**: `roomType, available, total` — `total` do backend tính (gồm phụ thu cuối tuần, lễ, khuyến mãi) hay frontend tự tính?
- [ ] **Reservation**: `reservationId, code, guestName, roomTypeName, checkInDate, checkOutDate, totalAmount, paidAmount, status, holdExpiresAt`
- [ ] **ReservationStatus**: `Pending, Confirmed, CheckedIn, CheckedOut, Cancelled, Expired, NoShow` — khớp với enum của backend?
- [ ] **RoomStatus**: `Available, Occupied, Dirty, Cleaning, Maintenance` (phòng Dirty → Cleaning → Available)
- [ ] **CreateReservation**: `branchId, roomTypeId, quantity, checkIn, checkOut, guestName, phone, note, promoCode`

## 3. Quy tắc nghiệp vụ cần backend xác nhận

- [ ] Thời gian giữ phòng: 15 phút kể từ khi tạo đơn (frontend đếm ngược theo `holdExpiresAt`)
- [ ] Phụ thu cuối tuần 15%, mã khuyến mãi: frontend chỉ hiển thị, nên để backend là nơi tính tiền chính thức
- [ ] Điều kiện hủy đơn (hủy miễn phí trước bao lâu, có hoàn tiền không)
- [ ] Lỗi mã khuyến mãi sai/hết hạn: trả mã nào, thông điệp gì
- [ ] Phân quyền: lễ tân chỉ thấy dữ liệu chi nhánh của mình; quản lý/admin thấy theo phạm vi nào

## 4. Khi chuyển sang API thật

1. Đặt `VITE_USE_MOCK_AUTH=false` trong `Hotel_Frontend/.env` để bật auth/profile backend; không tắt mock toàn cục vì nhiều API chưa tồn tại
2. Sửa đường dẫn và tên trường theo bảng đã đối chiếu ở trên
3. Chạy lại toàn bộ luồng: đăng nhập, tìm phòng, đặt phòng, thanh toán, đơn của tôi, hủy đơn
4. Xóa các đoạn `MOCK_*` và `localStorage` không còn dùng

API cập nhật trạng thái phòng cần chấp nhận `Cleaning` cho luồng dọn phòng: `Dirty` → `Cleaning` → `Available`.

Thông báo trả về theo cấu trúc `Notification[]` gồm `id, kind, title, message, href, createdAt, readAt`. Thông báo mock hiện lưu riêng trong trình duyệt. Khi dùng API thật, backend nên tạo thông báo theo sự kiện đơn (đặt mới, thanh toán, hủy, check-in, check-out) và chạy tác vụ nhắc nhận phòng trước ngày đến; frontend chỉ đọc danh sách và cập nhật trạng thái đã đọc.
## Tình trạng tích hợp backend hiện tại

Auth/profile và quản lý tài khoản admin đã nối vào endpoint thật bên trên. Cấu hình local trong `Hotel_Frontend/.env` dựa theo `.env.example`, sau đó khởi động lại Vite. Backend chưa có API chi nhánh, danh sách/chi tiết đơn, thanh toán, hủy đơn, sơ đồ phòng và lễ tân; giữ mock cho các luồng đó.

Backend tìm phòng hiện dùng `GET /api/v1/bookings/search?ngayNhan=&ngayTra=&soNguoiLon=&soTreEm=` và trả `RoomType[]` dạng `{ id, tenLoaiPhong, tongSoPhong, giaTienCoBan }`; chưa trả số phòng còn trống theo số lượng/chi nhánh và giá báo cuối. Giữ mock tìm phòng tới khi response được mở rộng. Giữ phòng dùng `POST /api/v1/bookings/hold` với `{ maLoaiPhong, soLuongPhong, ngayNhan, ngayTra, thongTinKhachHang }`, trả ID dạng chuỗi; chưa có API đọc đơn hoặc thanh toán để hoàn thành luồng đặt.

Báo cáo đã có API tại `/api/reports/**` nhưng đang dùng HTTP Basic và profile/database demo riêng, khác JWT auth chính. Chưa nối trang báo cáo vào API này.
