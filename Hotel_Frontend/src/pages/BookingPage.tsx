import { useState } from 'react'
import type { FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { BookingApi } from '../api/hotel'
import { useAuth } from '../store/auth'
import { nightsBetween, vnd } from '../lib'
import type { Availability, SearchParams } from '../types'

export default function BookingPage() {
  const state = useLocation().state as { search: SearchParams; item: Availability } | null
  const user = useAuth((s) => s.user)!
  const navigate = useNavigate()

  const [form, setForm] = useState({
    guestName: user.fullName,
    phone: user.phone ?? '',
    note: '',
    promoCode: '',
  })

  const create = useMutation({
    mutationFn: BookingApi.create,
    onSuccess: (r) => navigate(`/pay/${r.reservationId}`, { replace: true }),
  })

  // Vào thẳng /book mà chưa chọn phòng thì quay về trang about 
  if (!state) return <Navigate to="/search" replace />

  const { search, item } = state
  const phoneOk = /^0\d{9}$/.test(form.phone)

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!phoneOk) return
    create.mutate({
      branchId: search.branchId,
      roomTypeId: item.roomType.roomTypeId,
      quantity: search.rooms,
      checkIn: search.checkIn,
      checkOut: search.checkOut,
      ...form,
    })
  }

  return (
    <form className="card narrow" onSubmit={handleSubmit}>
      <h1>Thông tin đặt phòng</h1>
      <p>
        <b>{item.roomType.name}</b> × {search.rooms} phòng<br />
        <span className="mut">
          {search.checkIn} → {search.checkOut} ({nightsBetween(search.checkIn, search.checkOut)} đêm)
        </span>
      </p>

      <label>
        Họ tên
        <input required value={form.guestName} onChange={(e) => setForm({ ...form, guestName: e.target.value })} />
      </label>
      <label>
        Số điện thoại
        <input required value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
        {form.phone && !phoneOk && <small className="err">Số điện thoại gồm 10 số, bắt đầu bằng 0.</small>}
      </label>
      <label>
        Mã khuyến mãi (nếu có)
        <input value={form.promoCode} onChange={(e) => setForm({ ...form, promoCode: e.target.value.toUpperCase() })} />
      </label>
      <label>
        Yêu cầu đặc biệt
        <textarea rows={3} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
      </label>

      <p>Tạm tính: <span className="price">{vnd(item.total)}</span></p>

      {create.error && <p className="err">{(create.error as Error).message}</p>}
      <button className="btn primary" disabled={create.isPending || !phoneOk}>
        {create.isPending ? 'Đang giữ phòng...' : 'Tiếp tục thanh toán'}
      </button>
    </form>
  )
}