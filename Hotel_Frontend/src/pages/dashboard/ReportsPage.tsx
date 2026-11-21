import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { BranchApi, ReportApi, RoomApi } from '../../api/hotel'
import { vnd } from '../../lib'

type Tab = 'revenue' | 'occupancy' | 'bookings'
const TABS: { key: Tab; label: string }[] = [
  { key: 'revenue', label: 'Doanh thu' },
  { key: 'occupancy', label: 'Công suất' },
  { key: 'bookings', label: 'Đơn đặt phòng' },
]

export default function ReportsPage() {
  const [tab, setTab] = useState<Tab>('revenue')
  const q = useQuery({ queryKey: ['report-summary'], queryFn: ReportApi.summary, refetchInterval: 10000 })
  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })
  const rooms = useQuery({
    queryKey: ['rooms', 'all'],
    queryFn: () => RoomApi.list(undefined),
    enabled: tab === 'occupancy',
  })

  const s = q.data
  const max = Math.max(1, ...(s?.series.map((x) => x.revenue) ?? [1]))
  const total = s?.series.reduce((sum, x) => sum + x.revenue, 0) ?? 0
  const cancelRate = s && s.newBookings ? Math.round((s.cancelled / s.newBookings) * 100) : 0

  return (
    <>
      <h1>Báo cáo</h1>
      {q.isLoading && <p>Đang tải...</p>}
      {q.error && <p className="err">{(q.error as Error).message}</p>}

      <div className="sub-tabs">
        {TABS.map((x) => (
          <button key={x.key} className={`sub-tab ${tab === x.key ? 'on' : ''}`} onClick={() => setTab(x.key)}>
            {x.label}
          </button>
        ))}
      </div>

      <div key={tab} className="tab-anim">
        {s && tab === 'revenue' && (
          <>
            <div className="grid">
              <div className="card stat">Doanh thu hôm nay<div className="price">{vnd(s.revenueToday)}</div></div>
              <div className="card stat">Tổng 7 ngày<div className="price">{vnd(total)}</div></div>
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
              <p className="mut">Đơn vị trên cột: triệu đồng</p>
            </div>
          </>
        )}

        {s && tab === 'occupancy' && (
          <>
            <div className="grid">
              <div className="card stat">Công suất toàn hệ thống<div className="price">{s.occupancy}%</div></div>
            </div>
            <div className="card">
              <h2>Công suất theo chi nhánh</h2>
              {rooms.isLoading && <p>Đang tải...</p>}
              {branches.data?.map((b) => {
                const rs = (rooms.data ?? []).filter((r) => r.branchId === b.branchId)
                const occ = rs.filter((r) => r.status === 'Occupied').length
                const pct = rs.length ? Math.round((occ / rs.length) * 100) : 0
                return (
                  <div className="occ-row" key={b.branchId}>
                    <b>{b.name}</b>
                    <div className="occ-bar"><i style={{ width: `${pct}%` }} /></div>
                    <span>{pct}% · {occ}/{rs.length} phòng</span>
                  </div>
                )
              })}
            </div>
          </>
        )}

        {s && tab === 'bookings' && (
          <div className="grid">
            <div className="card stat">Tổng số đơn<div className="price">{s.newBookings}</div></div>
            <div className="card stat">Đơn đã hủy<div className="price">{s.cancelled}</div></div>
            <div className="card stat">Tỷ lệ hủy<div className="price">{cancelRate}%</div></div>
          </div>
        )}
      </div>
    </>
  )
}