import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

const vi = {
  nav: { about: 'Giới thiệu',search: 'Tìm phòng', myBookings: 'Đơn của tôi', login: 'Đăng nhập', logout: 'Đăng xuất' },
  dash: { rooms: 'Sơ đồ phòng', reservations: 'Đơn đặt phòng', reports: 'Báo cáo', pricing: 'Giá & khuyến mãi' },
  login: {
    title: 'Đăng nhập', email: 'Email', password: 'Mật khẩu',
    submit: 'Đăng nhập', loading: 'Đang xử lý...',
    slogan: 'Nghỉ ngơi trọn vẹn, đặt phòng dễ dàng',
    sub: 'Đăng nhập để quản lý đặt phòng và nhận ưu đãi dành riêng cho bạn.',
    perk1: 'Phòng trống cập nhật theo thời gian thực',
    perk2: 'Hủy miễn phí trước 48 giờ',
    perk3: 'Mã ưu đãi cho thành viên',
    demo: 'Dùng tài khoản demo:',
    show: 'Hiện',
    hide: 'Ẩn',
  },
}

const en = {
  nav: { about: 'About us',search: 'Search rooms', myBookings: 'My bookings', login: 'Sign in', logout: 'Sign out' },
  dash: { rooms: 'Room map', reservations: 'Reservations', reports: 'Reports', pricing: 'Pricing & promotions' },
  login: {
    slogan: 'Rest well, book easily',
    sub: 'Sign in to manage your bookings and get member-only offers.',
    perk1: 'Real-time room availability',
    perk2: 'Free cancellation up to 48 hours before',
    perk3: 'Promo codes for members',
    demo: 'Use a demo account:',
    show: 'Show',
    hide: 'Hide',
    title: 'Sign in', email: 'Email', password: 'Password',
    submit: 'Sign in', loading: 'Processing...',
  },
}

i18n.use(initReactI18next).init({
  resources: { vi: { translation: vi }, en: { translation: en } },
  lng: localStorage.getItem('lng') ?? 'vi',
  fallbackLng: 'vi',
  interpolation: { escapeValue: false },
})

export default i18n