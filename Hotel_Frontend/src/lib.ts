import type { ReservationStatus } from './types'
export const vnd = (n: number) => n.toLocaleString('vi-VN') + 'đ'

export const today = () => new Date().toISOString().slice(0, 10)

export const nightsBetween = (a: string, b: string) =>
  Math.max(0, Math.round((+new Date(b) - +new Date(a)) / 86400000))

export const STATUS_LABEL: Record<ReservationStatus, string> = {
  Pending: 'Chờ thanh toán',
  Confirmed: 'Đã xác nhận',
  CheckedIn: 'Đang ở',
  CheckedOut: 'Đã trả phòng',
  Cancelled: 'Đã hủy',
  Expired: 'Hết hạn giữ phòng',
  NoShow: 'Không đến',
}