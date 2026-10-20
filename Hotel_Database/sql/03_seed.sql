SET search_path=hotel,public;
INSERT INTO roles(code,name) VALUES ('GUEST','Khách hàng'),('RECEPTIONIST','Lễ tân'),('HOUSEKEEPING','Buồng phòng'),('MANAGER','Quản lý chi nhánh'),('ADMIN','Quản trị chuỗi');
INSERT INTO branches(name,address,city) VALUES ('Hà Nội Central','1 Tràng Tiền, Hà Nội','Hà Nội'),('Đà Nẵng Riverside','10 Bạch Đằng, Đà Nẵng','Đà Nẵng'),('Sài Gòn Grand','20 Đồng Khởi, TP.HCM','TP.HCM');
INSERT INTO room_types(branch_id,name,capacity,base_price,description) SELECT id,'Standard',2,900000,'Phòng tiêu chuẩn' FROM branches;
INSERT INTO room_types(branch_id,name,capacity,base_price,description) SELECT id,'Deluxe',3,1500000,'Phòng cao cấp' FROM branches;
INSERT INTO room_types(branch_id,name,capacity,base_price,description) SELECT id,'Suite',4,2900000,'Phòng suite' FROM branches;
INSERT INTO rooms(branch_id,room_type_id,room_number,floor)
 SELECT t.branch_id,t.id,(CASE t.name WHEN 'Standard' THEN '1' WHEN 'Deluxe' THEN '2' ELSE '3' END)||lpad(s::text,2,'0'),
 CASE t.name WHEN 'Standard' THEN 1 WHEN 'Deluxe' THEN 2 ELSE 3 END
 FROM room_types t CROSS JOIN generate_series(1,5) s;
INSERT INTO customers(full_name,email,phone) VALUES ('Nguyễn Văn A','demo-a@example.com','0900000001'),('Trần Thị B','demo-b@example.com','0900000002');
INSERT INTO services(branch_id,category,name,unit,current_price)
 SELECT id,'Giặt ủi','Giặt áo','cái',35000 FROM branches;
INSERT INTO services(branch_id,category,name,unit,current_price)
 SELECT id,'Minibar','Nước suối','chai',15000 FROM branches;
INSERT INTO promotions(code,name,kind,amount,max_discount,starts_at,ends_at,max_uses,per_customer_limit)
 VALUES('WELCOME10','Giảm 10%','PERCENT',10,500000,now()-interval '1 year',now()+interval '1 year',1000,2);
