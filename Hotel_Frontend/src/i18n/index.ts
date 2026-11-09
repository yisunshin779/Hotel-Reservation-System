import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { en } from './en'

// Tiếng Việt là ngôn ngữ gốc: chữ Việt trong code chính là khóa, chỉ cần khai báo các khóa kiểu "nav.xxx"
const vi: Record<string, string> = {
  'nav.about': 'Giới thiệu',
  'nav.search': 'Tìm phòng',
  'nav.myBookings': 'Đơn của tôi',
  'nav.login': 'Đăng nhập',
  'nav.logout': 'Đăng xuất',
  'dash.rooms': 'Sơ đồ phòng',
  'dash.reservations': 'Đơn đặt phòng',
  'dash.reports': 'Báo cáo',
  'dash.pricing': 'Giá & khuyến mãi',
}

i18n.use(initReactI18next).init({
  resources: { vi: { translation: vi }, en: { translation: en } },
  lng: localStorage.getItem('lng') ?? 'vi',
  fallbackLng: 'vi',
  keySeparator: false, // để chữ tiếng Việt có dấu chấm vẫn dùng làm khóa được
  nsSeparator: false,
  interpolation: { escapeValue: false },
})

i18n.on('languageChanged', (l) => { document.documentElement.lang = l })
document.documentElement.lang = i18n.language

export default i18n