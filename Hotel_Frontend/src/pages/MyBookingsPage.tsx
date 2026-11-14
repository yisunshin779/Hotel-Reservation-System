import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { BookingApi } from '../api/hotel'
import { vnd, STATUS_LABEL } from '../lib'


export default function MyBookingsPage() {
  const queryClient = useQueryClient()
  const list = useQuery({ queryKey: ['my-reservations'], queryFn: BookingApi.mine })

  const cancel = useMutation({
    mutationFn: BookingApi.cancel,
    // Hủy xong thì tải lại danh sách
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
                  <td>{vnd(b.totalAmount)}</td>
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
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}