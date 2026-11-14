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