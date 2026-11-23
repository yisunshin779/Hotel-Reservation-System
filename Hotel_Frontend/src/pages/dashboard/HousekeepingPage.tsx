import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BranchApi, RoomApi } from '../../api/hotel'
import { useAuth } from '../../store/auth'
import type { RoomStatus } from '../../types'

const FLOOR = (roomNumber: string) => roomNumber.slice(0, -2)

export default function HousekeepingPage() {
  const user = useAuth((state) => state.user)
  const client = useQueryClient()
  const isReceptionist = user?.role === 'receptionist'
  const [branchId, setBranchId] = useState(user?.branchId ?? 'hn')
  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })
  const rooms = useQuery({
    queryKey: ['rooms', branchId],
    queryFn: () => RoomApi.list(branchId),
    refetchInterval: 5000,
  })
  const update = useMutation({
    mutationFn: (value: { id: string; status: RoomStatus }) => RoomApi.setStatus(value.id, value.status),
    onSuccess: () => client.invalidateQueries({ queryKey: ['rooms'] }),
  })

  const all = rooms.data ?? []
  const queue = all
    .filter((room) => room.status === 'Dirty' || room.status === 'Cleaning')
    .sort((a, b) => a.roomNumber.localeCompare(b.roomNumber))
  const dirty = queue.filter((room) => room.status === 'Dirty').length
  const cleaning = queue.filter((room) => room.status === 'Cleaning').length
  const branchName = branches.data?.find((branch) => branch.branchId === branchId)?.name ?? ''

  return (
    <>
      <div className="house-head">
        <div>
          <h1>Công việc dọn phòng</h1>
          <p className="mut">Theo dõi phòng vừa trả và cập nhật khi phòng sẵn sàng đón khách.</p>
        </div>
        <span className="house-updated">Tự làm mới mỗi 5 giây</span>
      </div>

      {isReceptionist ? (
        <p className="branch-fixed">Chi nhánh của bạn: <b>{branchName}</b></p>
      ) : (
        <div className="sub-tabs">
          {branches.data?.map((branch) => (
            <button key={branch.branchId} className={`sub-tab ${branch.branchId === branchId ? 'on' : ''}`} onClick={() => setBranchId(branch.branchId)}>
              {branch.name}
            </button>
          ))}
        </div>
      )}

      <div className="house-summary">
        <div className="house-summary-card dirty"><span>Cần dọn</span><b>{dirty}</b></div>
        <div className="house-summary-card cleaning"><span>Đang dọn</span><b>{cleaning}</b></div>
        <div className="house-summary-card"><span>Phòng trong chi nhánh</span><b>{all.length}</b></div>
      </div>

      {rooms.isLoading && <p role="status">Đang tải danh sách phòng...</p>}
      {rooms.error && <p className="err" role="alert">{(rooms.error as Error).message}</p>}
      {update.error && <p className="err" role="alert">{(update.error as Error).message}</p>}
      {!rooms.isLoading && !rooms.error && queue.length === 0 && (
        <div className="card house-empty"><b>Không còn phòng trong hàng đợi dọn.</b><span>Tất cả phòng đã được xử lý.</span></div>
      )}

      {!!queue.length && (
        <div className="house-queue">
          {queue.map((room) => (
            <article className={`house-card ${room.status.toLowerCase()}`} key={room.roomId}>
              <div className="house-room-id"><span>Phòng</span><b>{room.roomNumber}</b></div>
              <div className="house-room-meta">
                <b>{room.roomTypeName}</b>
                <span>Tầng {FLOOR(room.roomNumber)}</span>
              </div>
              <span className={`house-status ${room.status.toLowerCase()}`}>
                {room.status === 'Dirty' ? 'Cần dọn' : 'Đang dọn'}
              </span>
              <button
                className={`btn ${room.status === 'Dirty' ? 'primary' : ''}`}
                disabled={update.isPending}
                onClick={() => update.mutate({ id: room.roomId, status: room.status === 'Dirty' ? 'Cleaning' : 'Available' })}
              >
                {update.isPending ? 'Đang cập nhật...' : room.status === 'Dirty' ? 'Nhận dọn phòng' : 'Hoàn tất · Sẵn sàng'}
              </button>
            </article>
          ))}
        </div>
      )}
    </>
  )
}