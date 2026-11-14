import { useQuery } from '@tanstack/react-query'
import { ReportApi } from '../../api/hotel'
import { vnd } from '../../lib'

export default function ReportsPage() {
  const q = useQuery({ queryKey: ['report-summary'], queryFn: ReportApi.summary, refetchInterval: 10000 })
  const s = q.data
  const max = Math.max(1, ...(s?.series.map((x) => x.revenue) ?? [1]))
  const total = s?.series.reduce((sum, x) => sum + x.revenue, 0) ?? 0

  return (
    <>
      <h1>Báo cáo</h1>
      {q.isLoading && <p>Đang tải...</p>}
      {q.error && <p className="err">{(q.error as Error).message}</p>}

      {s && (
        <>
          <div className="grid">
            <div className="card stat">Doanh thu hôm nay<div className="price">{vnd(s.revenueToday)}</div></div>
            <div className="card stat">Công suất phòng<div className="price">{s.occupancy}%</div></div>
            <div className="card stat">Tổng số đơn<div className="price">{s.newBookings}</div></div>
            <div className="card stat">Đơn đã hủy<div className="price">{s.cancelled}</div></div>
          </div>

          <div className="card">
            <h2>Doanh thu 7 ngày gần nhất</h2>
            <div className="bars">
              {s.series.map((x) => (
                <div key={x.date} style={{ height: `${(x.revenue / max) * 100}%` }} title={vnd(x.revenue)}>
                  <i>{(x.revenue / 1_000_000).toFixed(1)}</i>
                  <span>{x.date.slice(5)}</span>
                </div>
              ))}
            </div>
            <p>Tổng 7 ngày: <b>{vnd(total)}</b> <span className="mut">(đơn vị trên cột: triệu đồng)</span></p>
          </div>
        </>
      )}
    </>
  )
}