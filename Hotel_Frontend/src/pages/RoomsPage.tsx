import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { RoomTypeApi } from '../api/hotel'
import PageHead from '../components/PageHead'
import { vnd } from '../lib'

const LOOK: Record<string, { emoji: string; cls: string }> = {
  Standard: { emoji: '🛏️', cls: 'v1' },
  Deluxe: { emoji: '🌆', cls: 'v2' },
  Suite: { emoji: '👑', cls: 'v3' },
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
              <div className={`rv ${look.cls}`}><span className="rv-emoji">{look.emoji}</span></div>
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