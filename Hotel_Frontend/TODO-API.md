# TODO-API: Các điểm cần thống nhất với Backend

Frontend hiện chạy bằng dữ liệu giả (`USE_MOCK = true` trong `src/api/hotel.ts`).
Khi backend sẵn sàng, đối chiếu từng mục dưới đây rồi sửa `src/api/hotel.ts` và `src/types.ts`.
Đánh dấu `[x]` khi đã xác nhận và sửa xong.

## 0. Thông tin chung

- [ ] Cổng server và tiền tố chung (hiện giả định `http://localhost:8080/api`; sửa ở `src/api/client.ts` hoặc biến `VITE_API_BASE_URL`)
- [ ] Bật CORS cho `http://localhost:5173` (nhờ bạn backend)
- [ ] Cách xác thực: JWT gửi qua header `Authorization: Bearer <token>`? (hiện giả định có)
- [ ] Định dạng lỗi: hiện giả định `{ "message": "..." }`
- [ ] Mã lỗi: hiện giả định `401` hết phiên đăng nhập, `409` phòng vừa bị người khác đặt, `400` dữ liệu không hợp lệ
- [ ] Định dạng ngày: hiện giả định chuỗi `YYYY-MM-DD` (ngày) và ISO 8601 (thời điểm, ví dụ `holdExpiresAt`)
- [ ] Định dạng tiền: hiện giả định số nguyên VND (không có số thập phân)

## 1. Các API frontend đang gọi

Cột "Đường dẫn" là phần **giả định**, cần đối chiếu với Controller thật.

| Chức năng | Phương thức | Đường dẫn (giả định) | Gửi lên | Mong đợi trả về | Đã khớp |
|---|---|---|---|---|---|
| Đăng nhập | POST | `/auth/login` | `{ email, password }` | `{ token, user }` | [ ] |
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

## 2. Tên trường dữ liệu cần đối chiếu (`src/types.ts`)

Nếu backend đặt tên khác, sửa trong `types.ts` (hoặc đổi tên khi nhận dữ liệu).

- [ ] **User**: `id, fullName, email, role, branchId` — giá trị `role` là gì? (giả định `guest | receptionist | manager | admin`)
- [ ] **Branch**: `branchId, name, address`
- [ ] **RoomType**: `roomTypeId, name, capacity, basePrice, description, amenities[], images[]`
- [ ] **Availability**: `roomType, available, total` — `total` do backend tính (gồm phụ thu cuối tuần, lễ, khuyến mãi) hay frontend tự tính?
- [ ] **Reservation**: `reservationId, code, guestName, roomTypeName, checkInDate, checkOutDate, totalAmount, paidAmount, status, holdExpiresAt`
- [ ] **ReservationStatus**: `Pending, Confirmed, CheckedIn, CheckedOut, Cancelled, Expired, NoShow` — khớp với enum của backend?
- [ ] **RoomStatus**: `Available, Occupied, Dirty, Maintenance`
- [ ] **CreateReservation**: `branchId, roomTypeId, quantity, checkIn, checkOut, guestName, phone, note, promoCode`

## 3. Quy tắc nghiệp vụ cần backend xác nhận

- [ ] Thời gian giữ phòng: 15 phút kể từ khi tạo đơn (frontend đếm ngược theo `holdExpiresAt`)
- [ ] Phụ thu cuối tuần 15%, mã khuyến mãi: frontend chỉ hiển thị, nên để backend là nơi tính tiền chính thức
- [ ] Điều kiện hủy đơn (hủy miễn phí trước bao lâu, có hoàn tiền không)
- [ ] Lỗi mã khuyến mãi sai/hết hạn: trả mã nào, thông điệp gì
- [ ] Phân quyền: lễ tân chỉ thấy dữ liệu chi nhánh của mình; quản lý/admin thấy theo phạm vi nào

## 4. Khi chuyển sang API thật

1. Đổi `USE_MOCK = false` trong `src/api/hotel.ts`
2. Sửa đường dẫn và tên trường theo bảng đã đối chiếu ở trên
3. Chạy lại toàn bộ luồng: đăng nhập, tìm phòng, đặt phòng, thanh toán, đơn của tôi, hủy đơn
4. Xóa các đoạn `MOCK_*` và `localStorage` không còn dùng
