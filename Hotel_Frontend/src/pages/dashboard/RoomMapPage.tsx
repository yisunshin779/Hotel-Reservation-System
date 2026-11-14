import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BranchApi, RoomApi } from '../../api/hotel'
import { useAuth } from '../../store/auth'
import type { RoomStatus } from '../../types'

const LABEL: Record<RoomStatus, string> = {
  Available: 'Sẵn sàng',
  Occupied: 'Có khách',
  Dirty: 'Cần dọn',
  Maintenance: 'Bảo trì',
}

// Mỗi trạng thái chỉ được chuyển sang những trạng thái hợp lệ
const ACTIONS: Record<RoomStatus, { label: string; to: RoomStatus }[]> = {
  Available: [{ label: 'Khóa bảo trì', to: 'Maintenance' }],
  Occupied: [],
  Dirty: [{ label: 'Đã dọn xong', to: 'Available' }],
  Maintenance: [{ label: 'Mở lại phòng', to: 'Available' }],
}

const FILTERS: { key: RoomStatus | 'all'; label: string }[] = [
  { key: 'all', label: 'Tất cả' },
  { key: 'Available', label: LABEL.Available },
  { key: 'Occupied', label: LABEL.Occupied },
  { key: 'Dirty', label: LABEL.Dirty },
  { key: 'Maintenance', label: LABEL.Maintenance },
]

export default function RoomMapPage() {
  const user = useAuth((s) => s.user)
  const queryClient = useQueryClient()

  // Lễ tân chỉ làm việc ở chi nhánh của mình, quản lý và admin chọn được chi nhánh
  const isReceptionist = user?.role === 'receptionist'
  const [branchId, setBranchId] = useState(user?.branchId ?? 'hn')
  const [filter, setFilter] = useState<RoomStatus | 'all'>('all')

  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })
  const rooms = useQuery({
    queryKey: ['rooms', branchId],
    queryFn: () => RoomApi.list(branchId),
    refetchInterval: 5000, // tự tải lại mỗi 5 giây
  })
  const change = useMutation({
    mutationFn: (v: { id: string; status: RoomStatus }) => RoomApi.setStatus(v.id, v.status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rooms'] }),
  })

  const all = rooms.data ?? []
  const count = (s: RoomStatus) => all.filter((r) => r.status === s).length
  const shown = filter === 'all' ? all : all.filter((r) => r.status === filter)
  const floors = Array.from(new Set(shown.map((r) => r.roomNumber.slice(0, -2)))).sort((a, b) => +a - +b)
  const branchName = branches.data?.find((b) => b.branchId === branchId)?.name ?? ''
  const occupancy = all.length ? Math.round((count('Occupied') / all.length) * 100) : 0

  return (
    <>
      <h1>Sơ đồ phòng</h1>

      {isReceptionist ? (
        <p className="branch-fixed">📍 Chi nhánh của bạn: <b>{branchName}</b></p>
      ) : (
        <div className="tabs">
          {branches.data?.map((b) => (
            <button
              key={b.branchId}
              className={`tab ${b.branchId === branchId ? 'on' : ''}`}
              onClick={() => { setBranchId(b.branchId); setFilter('all') }}
            >
              {b.name}
            </button>
          ))}
        </div>
      )}

      <div className="grid">
        {(Object.keys(LABEL) as RoomStatus[]).map((s) => (
          <div className="card stat" key={s}>
            <span className={`dot ${s}`} /> {LABEL[s]}
            <div className="price">{count(s)}</div>
          </div>
        ))}
      </div>
      <p className="mut">Tổng {all.length} phòng · Công suất hiện tại {occupancy}%</p>

      <div className="fchips">
        {FILTERS.map((f) => (
          <button
            key={f.key}
            className={`fchip ${filter === f.key ? 'on' : ''}`}
            onClick={() => setFilter(f.key)}
          >
            {f.label}
          </button>
        ))}
      </div>

      {rooms.isLoading && <p>Đang tải...</p>}
      {rooms.error && <p className="err">{(rooms.error as Error).message}</p>}
      {change.error && <p className="err">{(change.error as Error).message}</p>}
      {!rooms.isLoading && shown.length === 0 && <div className="card">Không có phòng nào ở trạng thái này.</div>}

      {floors.map((f) => (
        <section key={f}>
          <h3 className="floor-title">Tầng {f}</h3>
          <div className="rooms">
            {shown.filter((r) => r.roomNumber.slice(0, -2) === f).map((r) => (
              <div key={r.roomId} className={`rm ${r.status}`}>
                <b>{r.roomNumber}</b>
                <small>{r.roomTypeName}</small>
                <small>{LABEL[r.status]}</small>
                {ACTIONS[r.status].map((a) => (
                  <button
                    key={a.to}
                    className="btn"
                    disabled={change.isPending}
                    onClick={() => change.mutate({ id: r.roomId, status: a.to })}
                  >
                    {a.label}
                  </button>
                ))}
              </div>
            ))}
          </div>
        </section>
      ))}
    </>
  )
}