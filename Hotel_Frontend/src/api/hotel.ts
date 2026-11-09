import type { User, Role, Branch, RoomType, SearchParams, Availability, Reservation, CreateReservation, Room, RoomStatus, ReportSummary, Promotion, PriceRow } from '../types'
import { api } from './client'
import { nightsBetween, today } from '../lib'

const USE_MOCK = true // đổi thành false khi backend có API thật

// ---------- Đăng nhập ----------
const MOCK_USERS: Record<string, { password: string; user: User }> = {
  'khach@example.com': { password: '12345678', user: { id: '1', fullName: 'Khách hàng', email: 'khach@example.com', role: 'guest' as Role } },
  'letan@example.com': { password: '12345678', user: { id: '2', fullName: 'Lễ tân', email: 'letan@example.com', role: 'receptionist' as Role, branchId: 'hn' } },
  'admin@example.com': { password: '12345678', user: { id: '3', fullName: 'Quản trị', email: 'admin@example.com', role: 'admin' as Role } },
}

// Tài khoản đăng ký thêm được lưu trong trình duyệt, ghi đè lên tài khoản mẫu nếu trùng email
const USERS_KEY = 'hrs-mock-users'
const RESET_KEY = 'hrs-mock-reset'
type StoredUser = { password: string; user: User }

const loadUsers = (): Record<string, StoredUser> => ({
  ...MOCK_USERS,
  ...JSON.parse(localStorage.getItem(USERS_KEY) ?? '{}'),
})
const saveUser = (email: string, s: StoredUser) => {
  const extra = JSON.parse(localStorage.getItem(USERS_KEY) ?? '{}')
  extra[email] = s
  localStorage.setItem(USERS_KEY, JSON.stringify(extra))
}
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms))

export const AuthApi = {
  async login(email: string, password: string): Promise<{ token: string; user: User }> {
    if (USE_MOCK) {
      await wait(400)
      const found = loadUsers()[email.trim().toLowerCase()]
      if (!found || found.password !== password) throw new Error('Thông tin đăng nhập không đúng.')
      return { token: 'mock-token', user: found.user }
    }
    const res = await api.post('/auth/login', { email, password })
    return res.data
  },

  async register(b: { fullName: string; email: string; phone: string; password: string }): Promise<{ token: string; user: User }> {
    if (USE_MOCK) {
      await wait(500)
      const email = b.email.trim().toLowerCase()
      if (loadUsers()[email]) throw new Error('Email này đã được đăng ký.')
      const user: User = { id: crypto.randomUUID(), fullName: b.fullName.trim(), email, role: 'guest' as Role }
      saveUser(email, { password: b.password, user })
      return { token: 'mock-token', user }
    }
    return (await api.post('/auth/register', b)).data
  },

  // Gửi mã khôi phục. Bản giả lập trả mã về để hiển thị thử; backend thật sẽ gửi qua email
  async forgot(email: string): Promise<{ demoCode?: string }> {
    if (USE_MOCK) {
      await wait(500)
      const mail = email.trim().toLowerCase()
      if (!loadUsers()[mail]) return {} // không tiết lộ email có tồn tại hay không
      const code = String(Math.floor(100000 + Math.random() * 900000))
      localStorage.setItem(RESET_KEY, JSON.stringify({ email: mail, code, expires: Date.now() + 10 * 60 * 1000 }))
      return { demoCode: code }
    }
    await api.post('/auth/forgot-password', { email })
    return {}
  },

  async resetPassword(email: string, code: string, newPassword: string): Promise<void> {
    if (USE_MOCK) {
      await wait(500)
      const mail = email.trim().toLowerCase()
      const saved = JSON.parse(localStorage.getItem(RESET_KEY) ?? 'null')
      if (!saved || saved.email !== mail || saved.code !== code.trim() || saved.expires < Date.now()) {
        throw new Error('Mã không đúng hoặc đã hết hạn.')
      }
      const found = loadUsers()[mail]
      saveUser(mail, { password: newPassword, user: found.user })
      localStorage.removeItem(RESET_KEY)
      return
    }
    await api.post('/auth/reset-password', { email, code, newPassword })
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

// ---------- Đặt phòng ----------
const KEY = 'hrs-mock-reservations'
const loadRes = (): Reservation[] => {
  const list: Reservation[] = JSON.parse(localStorage.getItem(KEY) ?? '[]')
  // Đơn Pending quá hạn giữ phòng thì chuyển thành Expired
  return list.map((r) =>
    r.status === 'Pending' && r.holdExpiresAt && +new Date(r.holdExpiresAt) < Date.now()
      ? { ...r, status: 'Expired' as const }
      : r
  )
}
const saveRes = (list: Reservation[]) => localStorage.setItem(KEY, JSON.stringify(list))

export const BookingApi = {
  async create(b: CreateReservation): Promise<Reservation> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 500))
      const rt = MOCK_ROOM_TYPES.find((x) => x.type.roomTypeId === b.roomTypeId)
      if (!rt) throw new Error('Hạng phòng không tồn tại.')
      let total = totalPrice(rt.type.basePrice, b.checkIn, b.checkOut, b.quantity)
      if (b.promoCode) {
        const promo = loadPromos().find((p) => p.code === b.promoCode && p.validTo >= today())
        if (!promo) throw new Error('Mã khuyến mãi không hợp lệ hoặc đã hết hạn.')
        total = Math.round((total * (100 - promo.percent)) / 100)
      }
      const res: Reservation = {
        reservationId: crypto.randomUUID(),
        branchId: b.branchId,
        code: 'HRS-' + Math.random().toString(36).slice(2, 8).toUpperCase(),
        guestName: b.guestName,
        roomTypeName: rt.type.name,
        checkInDate: b.checkIn,
        checkOutDate: b.checkOut,
        totalAmount: total,
        paidAmount: 0,
        status: 'Pending',
        holdExpiresAt: new Date(Date.now() + 15 * 60 * 1000).toISOString(),
      }
      saveRes([res, ...loadRes()])
      return res
    }
    return (await api.post('/reservations', b)).data
  },

  async get(id: string): Promise<Reservation> {
    if (USE_MOCK) {
      const r = loadRes().find((x) => x.reservationId === id)
      if (!r) throw new Error('Không tìm thấy đơn đặt phòng.')
      return r
    }
    return (await api.get(`/reservations/${id}`)).data
  },

  async pay(id: string, method: 'QR' | 'CASH'): Promise<void> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 600))
      const list = loadRes()
      const r = list.find((x) => x.reservationId === id)
      if (!r) throw new Error('Không tìm thấy đơn đặt phòng.')
      if (r.status === 'Expired') throw new Error('Đơn đã hết hạn giữ phòng.')
      r.status = 'Confirmed'
      r.holdExpiresAt = undefined
      r.paidAmount = method === 'QR' ? r.totalAmount : 0
      saveRes(list)
      return
    }
    await api.post(`/reservations/${id}/payments`, { method })
  },

    async mine(): Promise<Reservation[]> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 300))
      return loadRes()
    }
    return (await api.get('/reservations/me')).data
  },

  async cancel(id: string): Promise<void> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400))
      const list = loadRes()
      const r = list.find((x) => x.reservationId === id)
      if (!r) throw new Error('Không tìm thấy đơn đặt phòng.')
      if (r.status !== 'Pending' && r.status !== 'Confirmed') {
        throw new Error('Đơn này không thể hủy.')
      }
      r.status = 'Cancelled'
      saveRes(list)
      return
    }
    await api.post(`/reservations/${id}/cancel`)
  },
}

// ---------- Lễ tân: phòng (3 chi nhánh, mỗi chi nhánh 60 phòng) ----------
const ROOM_KEY = 'hrs-mock-rooms-v2'
const loadRooms = (): Room[] => {
  const raw = localStorage.getItem(ROOM_KEY)
  if (raw) return JSON.parse(raw)

  const rooms: Room[] = []
  ;['hn', 'dn', 'hcm'].forEach((branchId, bi) => {
    for (let floor = 1; floor <= 5; floor++) {
      for (let n = 1; n <= 12; n++) {
        // Mỗi tầng: 6 phòng Standard, 4 phòng Deluxe, 2 phòng Suite
        const type = n <= 6 ? 'Standard' : n <= 10 ? 'Deluxe' : 'Suite'
        // Trạng thái ban đầu rải đều cho sinh động
        const seed = (floor * 7 + n * 13 + bi * 5) % 10
        const status: RoomStatus =
          seed === 0 ? 'Maintenance' : seed <= 2 ? 'Dirty' : seed <= 5 ? 'Occupied' : 'Available'
        const no = `${floor}${String(n).padStart(2, '0')}`
        rooms.push({ roomId: `${branchId}-${no}`, branchId, roomNumber: no, status, roomTypeName: type })
      }
    }
  })
  localStorage.setItem(ROOM_KEY, JSON.stringify(rooms))
  return rooms
}
const saveRooms = (list: Room[]) => localStorage.setItem(ROOM_KEY, JSON.stringify(list))

export const RoomApi = {
  async list(branchId?: string): Promise<Room[]> {
    if (USE_MOCK) {
      const all = loadRooms()
      return branchId ? all.filter((r) => r.branchId === branchId) : all
    }
    return (await api.get('/staff/rooms', { params: { branchId } })).data
  },
  async setStatus(id: string, status: RoomStatus): Promise<void> {
    if (USE_MOCK) {
      const rooms = loadRooms()
      const r = rooms.find((x) => x.roomId === id)
      if (!r) throw new Error('Không tìm thấy phòng.')
      r.status = status
      saveRooms(rooms)
      return
    }
    await api.patch(`/staff/rooms/${id}/status`, { status })
  },
}
// ---------- Lễ tân: đơn đặt phòng ----------
export const StaffBookingApi = {
    async list(q: string, branchId?: string): Promise<Reservation[]> {
    if (USE_MOCK) {
      const s = q.trim().toLowerCase()
      return loadRes().filter(
        (r) =>
          (r.code + ' ' + r.guestName).toLowerCase().includes(s) &&
          (!branchId || !r.branchId || r.branchId === branchId)
      )
    }
    return (await api.get('/staff/reservations', { params: { q, branchId, size: 20 } })).data
  },

  async checkIn(id: string, idNumber: string): Promise<void> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400))
      if (idNumber.trim().length < 9) throw new Error('Số giấy tờ tùy thân không hợp lệ.')
      const list = loadRes()
      const res = list.find((x) => x.reservationId === id)
      if (!res) throw new Error('Không tìm thấy đơn đặt phòng.')
      if (res.status !== 'Confirmed') throw new Error('Chỉ đơn đã xác nhận mới check-in được.')
      const rooms = loadRooms()
      const room = rooms.find(
        (x) =>
          x.status === 'Available' &&
          x.roomTypeName === res.roomTypeName &&
          (!res.branchId || x.branchId === res.branchId)
      )
      if (!room) throw new Error(`Không còn phòng ${res.roomTypeName} sẵn sàng.`)
      room.status = 'Occupied'
      res.status = 'CheckedIn'
      res.roomNumber = room.roomNumber
      saveRooms(rooms)
      saveRes(list)
      return
    }
    await api.post(`/staff/reservations/${id}/check-in`, { idNumber })
  },

  async checkOut(id: string): Promise<void> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 400))
      const list = loadRes()
      const res = list.find((x) => x.reservationId === id)
      if (!res) throw new Error('Không tìm thấy đơn đặt phòng.')
      if (res.status !== 'CheckedIn') throw new Error('Đơn này chưa check-in.')
      const rooms = loadRooms()
      const room = rooms.find(
        (x) => x.roomNumber === res.roomNumber && (!res.branchId || x.branchId === res.branchId)
      )
      if (room) room.status = 'Dirty'
      res.status = 'CheckedOut'
      res.paidAmount = res.totalAmount // thu nốt phần còn lại tại quầy
      saveRooms(rooms)
      saveRes(list)
      return
    }
    await api.post(`/staff/reservations/${id}/check-out`)
  },
}

// ---------- Quản trị: khuyến mãi ----------
const PROMO_KEY = 'hrs-mock-promos'
const loadPromos = (): Promotion[] => {
  const raw = localStorage.getItem(PROMO_KEY)
  if (raw) return JSON.parse(raw)
  const def: Promotion[] = [{ code: 'HELLO10', percent: 10, validTo: '2099-12-31' }]
  localStorage.setItem(PROMO_KEY, JSON.stringify(def))
  return def
}
const savePromos = (list: Promotion[]) => localStorage.setItem(PROMO_KEY, JSON.stringify(list))

export const PromoApi = {
  async list(): Promise<Promotion[]> {
    if (USE_MOCK) return loadPromos()
    return (await api.get('/promotions')).data
  },
  async create(p: Promotion): Promise<void> {
    if (USE_MOCK) {
      await new Promise((r) => setTimeout(r, 300))
      const list = loadPromos()
      if (list.some((x) => x.code === p.code)) throw new Error('Mã khuyến mãi đã tồn tại.')
      savePromos([p, ...list])
      return
    }
    await api.post('/promotions', p)
  },
  async remove(code: string): Promise<void> {
    if (USE_MOCK) {
      savePromos(loadPromos().filter((x) => x.code !== code))
      return
    }
    await api.delete(`/promotions/${code}`)
  },
}

// ---------- Quản trị: bảng giá ----------
export const PriceApi = {
  async list(): Promise<PriceRow[]> {
    if (USE_MOCK) {
      return MOCK_ROOM_TYPES.map(({ type }) => ({
        roomTypeId: type.roomTypeId,
        name: type.name,
        normal: type.basePrice,
        weekend: Math.round(type.basePrice * 1.15),
        holiday: Math.round(type.basePrice * 1.4),
      }))
    }
    return (await api.get('/pricing')).data
  },
}

// ---------- Quản trị: báo cáo ----------
export const ReportApi = {
  async summary(): Promise<ReportSummary> {
    if (USE_MOCK) {
      const reservations = loadRes()
      const rooms = loadRooms()
      const revenueToday = reservations.reduce((s, r) => s + r.paidAmount, 0)
      const base = [42, 55, 38, 61, 70, 88].map((x) => x * 1_000_000) // 6 ngày trước (số giả)
      const series = [...base, revenueToday].map((revenue, i) => {
        const d = new Date()
        d.setDate(d.getDate() - (6 - i))
        return { date: d.toISOString().slice(0, 10), revenue }
      })
      const occupied = rooms.filter((r) => r.status === 'Occupied').length
      return {
        revenueToday,
        occupancy: Math.round((occupied / rooms.length) * 100),
        newBookings: reservations.length,
        cancelled: reservations.filter((r) => r.status === 'Cancelled').length,
        series,
      }
    }
    return (await api.get('/reports/summary')).data
  },
}