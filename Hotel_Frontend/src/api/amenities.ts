export type Amenity = {
  id: string
  name: string
  category: string
  description: string
  details: string[]
  hours: string
  image: string
}

export type AmenityBooking = {
  id: string
  userId: string
  amenityId: string
  date: string
  time: string
  guests: number
  note: string
  createdAt: string
}

// Danh mục và lịch hẹn demo: backend hiện chỉ có service_usages sau check-in, chưa có API đặt lịch tiện ích.
export const AMENITIES: Amenity[] = [
  { id: 'pool', name: 'Bể bơi', category: 'Thư giãn', description: 'Một khoảng nghỉ để bơi lội và thư giãn trong chuyến đi.', details: ['Chọn khung giờ dự kiến', 'Phù hợp cho cá nhân và gia đình', 'Thông tin khu vực cụ thể cần xác nhận tại chi nhánh'], hours: 'Theo lịch từng chi nhánh', image: '/pool.jpg' },
  { id: 'restaurant', name: 'Nhà hàng', category: 'Ẩm thực', description: 'Đặt bàn trước để sắp xếp một bữa ăn thuận tiện hơn.', details: ['Ghi số người khi đặt bàn', 'Có thể thêm yêu cầu đặc biệt', 'Thực đơn và giá cần xác nhận với chi nhánh'], hours: 'Theo lịch từng chi nhánh', image: '/restaurant.jpg' },
  { id: 'bar', name: 'Bar & lounge', category: 'Ẩm thực', description: 'Không gian trò chuyện và nghỉ ngơi sau một ngày khám phá.', details: ['Chọn ngày và khung giờ dự kiến', 'Ghi số lượng khách đi cùng', 'Độ tuổi và điều kiện phục vụ cần xác nhận tại chi nhánh'], hours: 'Theo lịch từng chi nhánh', image: '/bar.jpg' },
  { id: 'sport', name: 'Sân thể thao', category: 'Vận động', description: 'Lên kế hoạch cho một buổi vận động trong kỳ lưu trú.', details: ['Chọn khung giờ muốn sử dụng', 'Ghi số người tham gia', 'Loại sân và dụng cụ cần xác nhận tại chi nhánh'], hours: 'Theo lịch từng chi nhánh', image: '/sportzone.jpg' },
]

const KEY = 'hrs-amenity-bookings-demo'
const localDate = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const read = (): AmenityBooking[] => {
  try { return JSON.parse(localStorage.getItem(KEY) ?? '[]') as AmenityBooking[] }
  catch { return [] }
}

export const AmenityApi = {
  list: () => AMENITIES,
  get: (id: string) => AMENITIES.find((item) => item.id === id),
  mine: (userId: string) => read().filter((item) => item.userId === userId).sort((a, b) => `${a.date}${a.time}`.localeCompare(`${b.date}${b.time}`)),
  create(input: Omit<AmenityBooking, 'id' | 'createdAt'>) {
    if (!AMENITIES.some((item) => item.id === input.amenityId)) throw new Error('Dịch vụ không tồn tại.')
    if (!input.date || !input.time || input.date < localDate()) throw new Error('Vui lòng chọn ngày hợp lệ.')
    if (new Date(`${input.date}T${input.time}:00`).getTime() <= Date.now()) throw new Error('Vui lòng chọn thời gian trong tương lai.')
    if (!Number.isInteger(input.guests) || input.guests < 1 || input.guests > 20) throw new Error('Số khách phải từ 1 đến 20.')
    const list = read()
    if (list.some((item) => item.userId === input.userId && item.amenityId === input.amenityId && item.date === input.date && item.time === input.time)) {
      throw new Error('Bạn đã gửi yêu cầu cho khung giờ này.')
    }
    const booking: AmenityBooking = { ...input, id: crypto.randomUUID(), createdAt: new Date().toISOString() }
    localStorage.setItem(KEY, JSON.stringify([booking, ...list]))
    return booking
  },
  cancel(id: string, userId: string) {
    localStorage.setItem(KEY, JSON.stringify(read().filter((item) => !(item.id === id && item.userId === userId))))
  },
}
