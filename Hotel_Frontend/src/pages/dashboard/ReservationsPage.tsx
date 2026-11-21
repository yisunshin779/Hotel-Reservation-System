import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StaffBookingApi } from '../../api/hotel'
import StayDialog from '../../components/StayDialog'
import { useAuth } from '../../store/auth'
import { STATUS_LABEL, serviceTotal, vnd } from '../../lib'
import ExitMount from '../../components/ExitMount'

export default function ReservationsPage() {
  const [q, setQ] = useState('')
  const [openId, setOpenId] = useState<string | null>(null)
  const searchRef = useRef<HTMLInputElement>(null)
  const queryClient = useQueryClient()
  const user = useAuth((s) => s.user)
  const branchId = user?.role === 'receptionist' ? (user.branchId ?? 'hn') : undefined

  const list = useQuery({
    queryKey: ['staff-reservations', q, branchId],
    queryFn: () => StaffBookingApi.list(q, branchId),
  })

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['staff-reservations'] })
    queryClient.invalidateQueries({ queryKey: ['rooms'] })
  }
  const checkIn = useMutation({
    mutationFn: (v: { id: string; idNumber: string }) => StaffBookingApi.checkIn(v.id, v.idNumber),
    onSuccess: refresh,
  })
  const checkOut = useMutation({ mutationFn: StaffBookingApi.checkOut, onSuccess: refresh })

  // Phím tắt F2: đưa con trỏ vào ô tìm kiếm
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'F2') {
        e.preventDefault()
        searchRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const error = (checkIn.error || checkOut.error) as Error | null

  return (
    <>
      <h1>Đơn đặt phòng</h1>
      <input
        ref={searchRef}
        className="search"
        placeholder="Tìm theo mã đơn hoặc tên khách (F2)"
        value={q}
        onChange={(e) => setQ(e.target.value)}
      />

      {error && <p className="err">{error.message}</p>}
      {list.isLoading && <p>Đang tải...</p>}
      {list.data?.length === 0 && <div className="card">Không có đơn nào.</div>}

      {!!list.data?.length && (
        <div className="card wrap">
          <table>
            <thead>
              <tr>
                <th>Mã đơn</th><th>Khách</th><th>Hạng phòng</th><th>Phòng</th>
                <th>Ngày ở</th><th>Tổng</th><th>Đã thu</th><th>Trạng thái</th><th></th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((b) => {
                const total = b.totalAmount + serviceTotal(b.services)
                return (
                  <tr key={b.reservationId}>
                    <td>{b.code}</td>
                    <td>{b.guestName}</td>
                    <td>{b.roomTypeName}{(b.quantity ?? 1) > 1 ? ` × ${b.quantity}` : ''}</td>
                    <td>{b.roomNumbers?.join(', ') ?? b.roomNumber ?? '—'}</td>
                    <td>{b.checkInDate} → {b.checkOutDate}</td>
                    <td>{vnd(total)}</td>
                    <td>{vnd(b.paidAmount)}</td>
                    <td>{STATUS_LABEL[b.status]}</td>
                    <td>
                      {b.status === 'Confirmed' && (
                        <button
                          className="btn primary"
                          disabled={checkIn.isPending}
                          onClick={() => {
                            const idNumber = prompt('Nhập số CCCD/hộ chiếu của khách:')
                            if (idNumber) checkIn.mutate({ id: b.reservationId, idNumber })
                          }}
                        >
                          Check-in
                        </button>
                      )}
                      {b.status === 'CheckedIn' && (
                        <>
                          <button className="btn" onClick={() => setOpenId(b.reservationId)}>Dịch vụ / Đổi phòng</button>{' '}
                          <button
                            className="btn"
                            disabled={checkOut.isPending}
                            onClick={() => {
                              const due = total - b.paidAmount
                              const msg = due > 0 ? `Khách còn nợ ${vnd(due)}. Đã thu đủ và check-out?` : 'Xác nhận check-out?'
                              if (confirm(msg)) checkOut.mutate(b.reservationId)
                            }}
                          >
                            Check-out
                          </button>
                        </>
                      )}
                      {b.status === 'CheckedOut' && (
                        <button className="btn" onClick={() => setOpenId(b.reservationId)}>Hóa đơn</button>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      <ExitMount>{openId && <StayDialog id={openId} onClose={() => setOpenId(null)} />}</ExitMount>
    </>
  )
}