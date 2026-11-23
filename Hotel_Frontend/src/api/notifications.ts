import { api } from './client'
import { USE_MOCK } from './hotel'
import { useAuth } from '../store/auth'
import type { Reservation } from '../types'

export type NotificationKind = 'booking' | 'payment' | 'cancellation' | 'arrival' | 'departure' | 'reminder'
export interface NotificationItem {
  id: string
  recipientId?: string
  audience?: 'staff'
  kind: NotificationKind
  title: string
  message: string
  href: string
  createdAt: string
  readAt?: string
  eventKey?: string
}

const STORAGE_KEY = 'hrs-mock-notifications'
const getStored = (): NotificationItem[] => JSON.parse(localStorage.getItem(STORAGE_KEY) ?? '[]')
const setStored = (items: NotificationItem[]) => localStorage.setItem(STORAGE_KEY, JSON.stringify(items))

export const NotificationApi = {
  async listMine(): Promise<NotificationItem[]> {
    if (!USE_MOCK) return (await api.get('/notifications/me')).data
    const user = useAuth.getState().user
    if (!user) return []
    return getStored()
      .filter((item) => item.recipientId === user.id || (item.audience === 'staff' && user.role !== 'guest'))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
  },

  async markRead(id: string): Promise<void> {
    if (!USE_MOCK) {
      await api.patch(`/notifications/${id}/read`)
      return
    }
    const items = getStored()
    const item = items.find((x) => x.id === id)
    if (item && !item.readAt) item.readAt = new Date().toISOString()
    setStored(items)
    window.dispatchEvent(new Event('hrs-notifications-updated'))
  },

  async markAllRead(): Promise<void> {
    if (!USE_MOCK) {
      await api.patch('/notifications/me/read-all')
      return
    }
    const user = useAuth.getState().user
    const now = new Date().toISOString()
    const items = getStored().map((item) =>
      (item.recipientId === user?.id || (item.audience === 'staff' && user?.role !== 'guest')) && !item.readAt
        ? { ...item, readAt: now }
        : item
    )
    setStored(items)
    window.dispatchEvent(new Event('hrs-notifications-updated'))
  },

  // Local-only event bridge. In production the backend creates notifications from reservation events.
  recordMock(item: Omit<NotificationItem, 'id' | 'createdAt' | 'readAt'>): void {
    if (!USE_MOCK) return
    const items = getStored()
    if (item.eventKey && items.some((x) => x.eventKey === item.eventKey)) return
    items.unshift({ ...item, id: crypto.randomUUID(), createdAt: new Date().toISOString() })
    setStored(items.slice(0, 100))
    window.dispatchEvent(new Event('hrs-notifications-updated'))
  },

  notifyStaffSchedule(reservations: Reservation[]): void {
    if (!USE_MOCK) return
    const date = new Date().toISOString().slice(0, 10)
    reservations.forEach((reservation) => {
      if (reservation.status === 'Confirmed' && reservation.checkInDate === date) {
        this.recordMock({ audience: 'staff', kind: 'arrival', title: 'Khách sẽ nhận phòng hôm nay', message: `${reservation.guestName} · ${reservation.code} · ${reservation.roomTypeName}`, href: '/dashboard/reservations', eventKey: `staff-arrival:${reservation.reservationId}:${date}` })
      }
      if (reservation.status === 'CheckedIn' && reservation.checkOutDate === date) {
        this.recordMock({ audience: 'staff', kind: 'departure', title: 'Khách sẽ trả phòng hôm nay', message: `${reservation.guestName} · ${reservation.code}`, href: '/dashboard/reservations', eventKey: `staff-departure:${reservation.reservationId}:${date}` })
      }
    })
  },
  remindUpcoming(reservations: Reservation[]): void {
    if (!USE_MOCK) return
    const tomorrow = new Date()
    tomorrow.setDate(tomorrow.getDate() + 1)
    const date = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`
    reservations
      .filter((reservation) => reservation.status === 'Confirmed' && reservation.checkInDate === date)
      .forEach((reservation) => this.recordMock({
        recipientId: reservation.guestId ?? useAuth.getState().user?.id,
        kind: 'reminder',
        title: 'Ngày mai bạn nhận phòng',
        message: `Đơn ${reservation.code} · ${reservation.roomTypeName}. Hẹn gặp bạn tại Sứ Hotel!`,
        href: '/my-bookings',
        eventKey: `arrival-reminder:${reservation.reservationId}:${date}`,
      }))
  },
}