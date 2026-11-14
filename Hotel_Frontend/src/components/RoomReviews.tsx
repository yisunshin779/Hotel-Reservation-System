import { useQuery } from '@tanstack/react-query'
import { ReviewApi } from '../api/hotel'
import Stars from './Stars'

// Khối đánh giá hiển thị trên trang chi tiết phòng
export default function RoomReviews({ roomName }: { roomName: string }) {
  const q = useQuery({ queryKey: ['room-reviews', roomName], queryFn: () => ReviewApi.byRoomType(roomName) })
  const list = q.data ?? []
  const avg = list.length ? list.reduce((s, r) => s + r.rating, 0) / list.length : 0

  return (
    <>
      <h2 className="section-title">Đánh giá của khách</h2>
      <div className="panel">
        {q.isLoading && <p className="mut">Đang tải...</p>}
        {!q.isLoading && list.length === 0 && <p className="mut" style={{ margin: 0 }}>Chưa có đánh giá nào cho hạng phòng này.</p>}

        {list.length > 0 && (
          <>
            <div className="rev-sum">
              <b>{avg.toFixed(1)}</b>
              <div>
                <Stars value={avg} size={20} />
                <small className="mut">{list.length} đánh giá</small>
              </div>
            </div>
            {list.slice(0, 5).map((r) => (
              <div className="rev-item" key={r.id}>
                <div className="rev-top">
                  <b>{r.guestName}</b>
                  <Stars value={r.rating} size={14} />
                  <small className="mut">{new Date(r.at).toLocaleDateString('vi-VN')}</small>
                </div>
                {r.comment && <p>{r.comment}</p>}
              </div>
            ))}
          </>
        )}
      </div>
    </>
  )
}