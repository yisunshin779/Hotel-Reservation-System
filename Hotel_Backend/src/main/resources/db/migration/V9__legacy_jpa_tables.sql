CREATE TABLE IF NOT EXISTS hotel.account (
    id uuid PRIMARY KEY,
    ho_ten varchar(255) NOT NULL,
    email varchar(100) NOT NULL UNIQUE,
    so_dien_thoai varchar(20),
    cccd varchar(12) UNIQUE,
    mat_khau varchar(255),
    auth_provider varchar(20) NOT NULL,
    vai_tro varchar(20) NOT NULL,
    trang_thai varchar(20) NOT NULL,
    so_lan_dang_nhap_sai integer NOT NULL DEFAULT 0,
    thoi_gian_khoa timestamp(6),
    thoi_gian_tao timestamp(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS hotel.verification_token (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES hotel.account(id),
    token varchar(255) NOT NULL UNIQUE,
    loai_token varchar(20) NOT NULL,
    ngay_het_han timestamp(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS hotel.room_type (
    id varchar(50) PRIMARY KEY,
    ten_loai_phong varchar(255) NOT NULL,
    tong_so_phong integer NOT NULL,
    gia_tien_co_ban numeric(38, 2) NOT NULL
);

CREATE TABLE IF NOT EXISTS hotel.reservation (
    id uuid PRIMARY KEY,
    ma_loai_phong varchar(255) NOT NULL,
    so_luong_phong integer NOT NULL,
    ngay_nhan date NOT NULL,
    ngay_tra date NOT NULL,
    trang_thai varchar(50) NOT NULL,
    thoi_gian_tao timestamp(6) NOT NULL
);
