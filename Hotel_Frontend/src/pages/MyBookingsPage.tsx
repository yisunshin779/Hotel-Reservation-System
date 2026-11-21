import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { BookingApi } from '../api/hotel'
import ReviewDialog from '../components/ReviewDialog'
import { STATUS_LABEL, serviceTotal, vnd } from '../lib'
import type { Reservation } from '../types'
import ExitMount from '../components/ExitMount'

export default function MyBookingsPage() {
  const queryClient = useQueryClient()
  const [reviewing, setReviewing] = useState<Reservation | null>(null)
  const list = useQuery({ queryKey: ['my-reservations'], queryFn: BookingApi.mine })

  const cancel = useMutation({
    mutationFn: BookingApi.cancel,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['my-reservations'] }),
  })

  return (
    <>
      <h1>Đơn của tôi</h1>

      {list.isLoading && <p>Đang tải...</p>}
      {list.error && <p className="err">{(list.error as Error).message}</p>}
      {cancel.error && <p className="err">{(cancel.error as Error).message}</p>}

      {list.data?.length === 0 && (
        <div className="card">
          Bạn chưa có đơn đặt phòng nào. <Link to="/search">Tìm phòng ngay</Link>
        </div>
      )}

      {!!list.data?.length && (
        <div className="card wrap">
          <table>
            <thead>
              <tr>
                <th>Mã đơn</th>
                <th>Hạng phòng</th>
                <th>Ngày ở</th>
                <th>Tổng tiền</th>
                <th>Trạng thái</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {list.data.map((b) => (
                <tr key={b.reservationId}>
                  <td>{b.code}</td>
                  <td>{b.roomTypeName}{(b.quantity ?? 1) > 1 ? ` × ${b.quantity}` : ''}</td>
                  <td>{b.checkInDate} → {b.checkOutDate}</td>
                  <td>{vnd(b.totalAmount + serviceTotal(b.services))}</td>
                  <td>{STATUS_LABEL[b.status]}</td>
                  <td>
                    {b.status === 'Pending' && (
                      <Link className="btn primary" to={`/pay/${b.reservationId}`}>Thanh toán</Link>
                    )}{' '}
                    {(b.status === 'Pending' || b.status === 'Confirmed') && (
                      <button
                        className="btn"
                        disabled={cancel.isPending}
                        onClick={() => {
                          if (confirm('Bạn chắc chắn muốn hủy đơn này?')) cancel.mutate(b.reservationId)
                        }}
                      >
                        Hủy
                      </button>
                    )}
                    {b.status === 'CheckedOut' && !b.reviewed && (
                      <button className="btn primary" onClick={() => setReviewing(b)}>⭐ Đánh giá</button>
                    )}
                    {b.status === 'CheckedOut' && b.reviewed && <span className="mut">✓ Đã đánh giá</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
     <ExitMount>
      {reviewing && (
        <ReviewDialog
          reservationId={reviewing.reservationId}
          code={reviewing.code}
          roomTypeName={reviewing.roomTypeName}
          onClose={() => setReviewing(null)}
        />
      )}
    </ExitMount>
    </>
  )
}