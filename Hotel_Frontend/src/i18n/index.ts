import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

const vi = {
  nav: { search: 'Tìm phòng', myBookings: 'Đơn của tôi', login: 'Đăng nhập', logout: 'Đăng xuất' },
  dash: { rooms: 'Sơ đồ phòng', reservations: 'Đơn đặt phòng', reports: 'Báo cáo', pricing: 'Giá & khuyến mãi' },
  login: {
    title: 'Đăng nhập', email: 'Email', password: 'Mật khẩu',
    submit: 'Đăng nhập', loading: 'Đang xử lý...',
  },
}

const en = {
  nav: { search: 'Search rooms', myBookings: 'My bookings', login: 'Sign in', logout: 'Sign out' },
  dash: { rooms: 'Room map', reservations: 'Reservations', reports: 'Reports', pricing: 'Pricing & promotions' },
  login: {
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