import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { RoomTypeApi } from '../api/hotel'
import PageHead from '../components/PageHead'
import { vnd } from '../lib'

const LOOK: Record<string, { emoji: string; cls: string; image: string }> = {
  Standard: { emoji: '🛏️', cls: 'v1', image: 'https://images.unsplash.com/photo-1611892440504-42a792e24d32?auto=format&fit=crop&w=1000&q=85' },
  Deluxe: { emoji: '🌆', cls: 'v2', image: 'https://images.unsplash.com/photo-1616594039964-ae9021a400a0?auto=format&fit=crop&w=1000&q=85' },
  Suite: { emoji: '👑', cls: 'v3', image: 'https://images.unsplash.com/photo-1616486338812-3dadae4b4ace?auto=format&fit=crop&w=1000&q=85' },
}

export default function RoomsPage() {
  const q = useQuery({ queryKey: ['room-types'], queryFn: RoomTypeApi.list })

  return (
    <>
      <PageHead
        title="Hạng phòng"
        sub="Ba hạng phòng áp dụng chung cho cả ba chi nhánh. Chọn một hạng để xem tiện nghi, giá và đánh giá."
        crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Hạng phòng' }]}
      />
      {q.isLoading && <p>Đang tải...</p>}
      {q.error && <p className="err">{(q.error as Error).message}</p>}

      <div className="results">
        {q.data?.map((r) => {
          const look = LOOK[r.name] ?? { emoji: '🛏️', cls: 'v1' }
          return (
            <div className="room-card" key={r.roomTypeId}>
              <div className={`rv ${look.cls}`}><img src={look.image} alt={`Phòng ${r.name}`} loading="lazy" /><span className="rv-emoji" aria-hidden="true">{look.emoji}</span></div>
              <div className="rc-info">
                <h3>{r.name}</h3>
                <p className="rc-desc">{r.description}</p>
                <div>
                  {r.amenities.map((x) => <span className="tag" key={x}>{x}</span>)}
                  <span className="tag">Tối đa {r.capacity} người</span>
                </div>
              </div>
              <div className="rc-price">
                <small>Giá từ</small>
                <div className="price">{vnd(r.basePrice)}</div>
                <small>mỗi đêm</small>
                <Link className="btn primary" to={`/rooms/${r.roomTypeId}`}>Xem chi tiết</Link>
              </div>
            </div>
          )
        })}
      </div>
    </>
  )
}
