import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { RoomApi } from '../../api/hotel'
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

export default function RoomMapPage() {
  const queryClient = useQueryClient()
  const rooms = useQuery({
    queryKey: ['rooms'],
    queryFn: RoomApi.list,
    refetchInterval: 5000, // tự tải lại mỗi 5 giây
  })
  const change = useMutation({
    mutationFn: (v: { id: string; status: RoomStatus }) => RoomApi.setStatus(v.id, v.status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['rooms'] }),
  })

  const count = (s: RoomStatus) => rooms.data?.filter((r) => r.status === s).length ?? 0

  return (
    <>
      <h1>Sơ đồ phòng</h1>

      <div className="grid">
        {(Object.keys(LABEL) as RoomStatus[]).map((s) => (
          <div className="card stat" key={s}>
            <span className={`dot ${s}`} /> {LABEL[s]}
            <div className="price">{count(s)}</div>
          </div>
        ))}
      </div>

      {rooms.isLoading && <p>Đang tải...</p>}
      {rooms.error && <p className="err">{(rooms.error as Error).message}</p>}
      {change.error && <p className="err">{(change.error as Error).message}</p>}

      <div className="rooms">
        {rooms.data?.map((r) => (
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
    </>
  )
}