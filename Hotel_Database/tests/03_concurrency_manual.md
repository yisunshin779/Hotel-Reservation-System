# Kiểm thử đồng thời hai phiên PostgreSQL

Seed hiện có 5 phòng Deluxe tại chi nhánh Hà Nội. Để tạo tranh chấp, dùng hai cửa sổ psql riêng, cùng đặt **5 phòng Deluxe** cho cùng ngày. Thực hiện đồng thời các lệnh bên dưới, thay mã đặt khác nhau.

**Phiên A** (giữ transaction 15 giây):
```sql
BEGIN;
SELECT hotel.make_reservation(1,1,CURRENT_DATE+20,CURRENT_DATE+21,
 '[{"room_type_id":2,"quantity":5}]','RACE-A',false);
SELECT pg_sleep(15);
COMMIT;
```

**Phiên B** (chạy trong lúc A đang ngủ):
```sql
BEGIN;
SELECT hotel.make_reservation(2,1,CURRENT_DATE+20,CURRENT_DATE+21,
 '[{"room_type_id":2,"quantity":5}]','RACE-B',false);
COMMIT;
```

Kỳ vọng: B phải chờ advisory lock; sau khi A commit, B lỗi `Not enough inventory`. Trường hợp A rollback, B có thể thành công. Nếu lệnh chạy vào một ngày khác với múi giờ chi nhánh, chọn ngày cụ thể trong tương lai.

**Lưu ý:** ID `room_type_id=2` chỉ đúng khi database vừa tạo từ bộ seed này. Kiểm tra lại bằng `SELECT id FROM hotel.room_types WHERE branch_id=1 AND name='Deluxe';`.
