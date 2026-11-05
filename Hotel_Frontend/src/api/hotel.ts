import { api } from './client'
import { nightsBetween } from '../lib'
import type { User, Role, Branch, RoomType, SearchParams, Availability } from '../types'

const USE_MOCK = true // đổi thành false khi backend có API thật

// ---------- Đăng nhập ----------
const MOCK_USERS: Record<string, { password: string; user: User }> = {
  'khach@example.com': { password: '12345678', user: { id: '1', fullName: 'Khách hàng', email: 'khach@example.com', role: 'guest' as Role } },
  'letan@example.com': { password: '12345678', user: { id: '2', fullName: 'Lễ tân', email: 'letan@example.com', role: 'receptionist' as Role } },
  'admin@example.com': { password: '12345678', user: { id: '3', fullName: 'Quản trị', email: 'admin@example.com', role: 'admin' as Role } },
}

export const AuthApi = {
  async login(email: string, password: string): Promise<{ token: string; user: User }> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400))
      const found = MOCK_USERS[email]
      if (!found || found.password !== password) throw new Error('Thông tin đăng nhập không đúng.')
      return { token: 'mock-token', user: found.user }
    }
    const res = await api.post('/auth/login', { email, password })
    return res.data
  },
}

// ---------- Chi nhánh và tìm phòng ----------
const MOCK_BRANCHES: Branch[] = [
  { branchId: 'hn', name: 'Hà Nội – Hoàn Kiếm', address: '' },
  { branchId: 'dn', name: 'Đà Nẵng – Mỹ Khê', address: '' },
  { branchId: 'hcm', name: 'TP.HCM – Quận 1', address: '' },
]

const MOCK_ROOM_TYPES: { type: RoomType; available: number }[] = [
  { available: 20, type: { roomTypeId: '1', name: 'Standard', capacity: 2, basePrice: 850000, description: 'Phòng gọn gàng, đầy đủ tiện nghi cho chuyến đi ngắn.', amenities: ['Giường đôi', '25m²', 'Wifi'], images: [] } },
  { available: 8, type: { roomTypeId: '2', name: 'Deluxe', capacity: 3, basePrice: 1350000, description: 'Không gian rộng, tầm nhìn đẹp, phù hợp gia đình nhỏ.', amenities: ['View thành phố', '32m²', 'Bồn tắm'], images: [] } },
  { available: 2, type: { roomTypeId: '3', name: 'Suite', capacity: 4, basePrice: 2800000, description: 'Hạng cao cấp với phòng khách riêng và dịch vụ ưu tiên.', amenities: ['Phòng khách riêng', '60m²', 'Lounge'], images: [] } },
]

// Tính tiền: mỗi đêm cuối tuần (T6, T7) phụ thu 15%
function totalPrice(basePrice: number, checkIn: string, checkOut: string, rooms: number) {
  let sum = 0
  const d = new Date(checkIn)
  for (let i = 0; i < nightsBetween(checkIn, checkOut); i++) {
    const weekend = d.getDay() === 5 || d.getDay() === 6
    sum += basePrice * (weekend ? 1.15 : 1)
    d.setDate(d.getDate() + 1)
  }
  return Math.round(sum * rooms)
}

export const BranchApi = {
  async list(): Promise<Branch[]> {
    if (USE_MOCK) return MOCK_BRANCHES
    return (await api.get('/branches')).data
  },
}

export const SearchApi = {
  async search(p: SearchParams): Promise<Availability[]> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 500))
      const perRoom = Math.ceil((p.adults + p.children) / p.rooms)
      return MOCK_ROOM_TYPES
        .filter((x) => x.type.capacity >= perRoom && x.available >= p.rooms)
        .map((x) => ({
          roomType: x.type,
          available: x.available,
          total: totalPrice(x.type.basePrice, p.checkIn, p.checkOut, p.rooms),
        }))
    }
    return (await api.get('/rooms/availability', { params: p })).data
  },
}