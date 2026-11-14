import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ReviewApi } from '../../api/hotel'
import Stars from '../../components/Stars'

export default function ReviewsPage() {
  const [filter, setFilter] = useState(0) // 0 = tất cả
  const q = useQuery({ queryKey: ['reviews'], queryFn: ReviewApi.list })

  const all = q.data ?? []
  const avg = all.length ? all.reduce((s, r) => s + r.rating, 0) / all.length : 0
  const count = (n: number) => all.filter((r) => r.rating === n).length
  const shown = filter ? all.filter((r) => r.rating === filter) : all

  return (
    <>
      <h1>Đánh giá của khách</h1>
      {q.isLoading && <p>Đang tải...</p>}
      {q.error && <p className="err">{(q.error as Error).message}</p>}

      <div className="grid two">
        <div className="card">
          <span className="mut">Điểm trung bình</span>
          <div className="rev-sum">
            <b>{avg.toFixed(1)}</b>
            <div>
              <Stars value={avg} size={22} />
              <small className="mut">{all.length} đánh giá</small>
            </div>
          </div>
        </div>

        <div className="card">
          {[5, 4, 3, 2, 1].map((n) => (
            <div className="dist-row" key={n}>
              <span>{n} ★</span>
              <div className="dist-bar"><i style={{ width: `${all.length ? (count(n) / all.length) * 100 : 0}%` }} /></div>
              <span className="mut">{count(n)}</span>
            </div>
          ))}
        </div>
      </div>

      <div className="fchips">
        {[0, 5, 4, 3, 2, 1].map((n) => (
          <button key={n} className={`fchip ${filter === n ? 'on' : ''}`} onClick={() => setFilter(n)}>
            {n === 0 ? 'Tất cả' : `${n} sao`}
          </button>
        ))}
      </div>

      {!q.isLoading && shown.length === 0 && <div className="card">Chưa có đánh giá nào.</div>}

      {shown.map((r) => (
        <div className="card rev-item" key={r.id}>
          <div className="rev-top">
            <b>{r.guestName}</b>
            <Stars value={r.rating} size={15} />
            <span className="mut">Phòng {r.roomTypeName} · {r.code}</span>
            <small className="mut" style={{ marginLeft: 'auto' }}>{new Date(r.at).toLocaleDateString('vi-VN')}</small>
          </div>
          {r.comment ? <p>{r.comment}</p> : <p className="mut">(Không có nhận xét)</p>}
        </div>
      ))}
    </>
  )
}