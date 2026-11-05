import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { BookingApi } from './api/hotel'
import { vnd } from './lib'

export default function PaymentPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [method, setMethod] = useState<'QR' | 'CASH'>('QR')
  const [now, setNow] = useState(Date.now())

  const q = useQuery({ queryKey: ['reservation', id], queryFn: () => BookingApi.get(id) })
  const pay = useMutation({
    mutationFn: () => BookingApi.pay(id, method),
    onSuccess: () => navigate('/my-bookings', { replace: true }),
  })

  // Cứ nửa giây cập nhật "bây giờ" một lần để đồng hồ chạy
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(t)
  }, [])

  if (q.isLoading) return <p>Đang tải...</p>
  if (q.error) return <p className="err">{(q.error as Error).message}</p>
  const r = q.data!

  const left = r.holdExpiresAt
    ? Math.max(0, Math.round((+new Date(r.holdExpiresAt) - now) / 1000))
    : 0
  const expired = r.status === 'Expired' || r.status === 'Cancelled' || (r.status === 'Pending' && left === 0)

  if (expired) {
    return (
      <div className="card narrow">
        <h1>Đơn đã hết hạn giữ phòng</h1>
        <p className="mut">Phòng đã được trả lại hệ thống. Bạn hãy tìm và đặt lại.</p>
        <button className="btn primary" onClick={() => navigate('/')}>Tìm phòng lại</button>
      </div>
    )
  }

  const mm = String(Math.floor(left / 60)).padStart(2, '0')
  const ss = String(left % 60).padStart(2, '0')

  return (
    <div className="card narrow">
      <h1>Thanh toán</h1>
      <p>Mã đơn: <b>{r.code}</b></p>
      <p className={left < 120 ? 'err' : ''}>Giữ phòng còn <b>{mm}:{ss}</b></p>
      <p className="price">{vnd(r.totalAmount)}</p>

      <label>
        <input type="radio" checked={method === 'QR'} onChange={() => setMethod('QR')} /> Chuyển khoản QR
      </label>
      <label>
        <input type="radio" checked={method === 'CASH'} onChange={() => setMethod('CASH')} /> Tiền mặt tại khách sạn
      </label>

      {method === 'QR' && <div className="qr">▦ Mã QR mô phỏng<br />Nội dung chuyển khoản: {r.code}</div>}

      {pay.error && <p className="err">{(pay.error as Error).message}</p>}
      <button className="btn primary" disabled={pay.isPending} onClick={() => pay.mutate()}>
        {pay.isPending ? 'Đang xử lý...' : method === 'QR' ? 'Tôi đã thanh toán' : 'Xác nhận đặt phòng'}
      </button>
    </div>
  )
}