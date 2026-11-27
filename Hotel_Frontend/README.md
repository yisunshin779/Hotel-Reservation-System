# Hotel Frontend

Giao diện web cho Hệ thống đặt phòng khách sạn (React + TypeScript + Vite).

## Chạy thử
    cd Hotel_Frontend
    npm install
    npm run dev
Mở http://localhost:5173

## Tài khoản demo (dữ liệu giả)
- Khách: khach@example.com / 12345678
- Lễ tân: letan@example.com / 12345678
- Quản trị: admin@example.com / 12345678

## Nối backend
Hiện chạy bằng dữ liệu giả (USE_MOCK = true trong src/api/hotel.ts).
Các điểm cần đối chiếu với backend ghi trong TODO-API.md.

## Docker
docker build -t hotel-frontend .
docker run -p 8081:80 hotel-frontend
## Nối API tài khoản backend

1. Sao chép `.env.example` thành `.env`.
2. Đảm bảo backend chạy ở `http://localhost:8080` và frontend ở `http://localhost:5173`.
3. Khởi động lại Vite sau khi đổi biến môi trường. `VITE_USE_MOCK_AUTH=false` bật đăng nhập/đăng ký/hồ sơ backend; các module chưa có API khớp vẫn dùng mock.
4. Đăng ký cần mở email xác thực trước khi đăng nhập. Quên mật khẩu gửi liên kết tới `/reset-password?token=...`.

Xem `TODO-API.md` để biết các module còn mock và schema API còn thiếu.

Khi dùng auth backend, trang `/dashboard/accounts` cũng gọi API quản trị tài khoản để xem danh sách, đổi vai trò và khóa/mở khóa tài khoản (chỉ dành cho admin).
