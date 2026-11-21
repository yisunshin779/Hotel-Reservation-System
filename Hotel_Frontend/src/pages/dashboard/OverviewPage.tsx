import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { BranchApi, ReportApi, RoomApi, StaffBookingApi } from '../../api/hotel'
import { useAuth } from '../../store/auth'
import { today, vnd } from '../../lib'

export default function OverviewPage() {
  const user = useAuth((s) => s.user)
  const isRecep = user?.role === 'receptionist'
  // Lễ tân chỉ thấy chi nhánh của mình, quản lý và admin thấy toàn hệ thống
  const branchId = isRecep ? (user?.branchId ?? 'hn') : undefined
  const t = today()

  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })
  const rooms = useQuery({
    queryKey: ['rooms', branchId ?? 'all'],
    queryFn: () => RoomApi.list(branchId),
    refetchInterval: 5000,
  })
  const res = useQuery({
    queryKey: ['staff-reservations', '', branchId],
    queryFn: () => StaffBookingApi.list('', branchId),
  })
  const report = useQuery({ queryKey: ['report-summary'], queryFn: ReportApi.summary, enabled: !isRecep })

  const allRooms = rooms.data ?? []
  const list = res.data ?? []
  const count = (s: string) => allRooms.filter((r) => r.status === s).length
  const occupancy = allRooms.length ? Math.round((count('Occupied') / allRooms.length) * 100) : 0
  const arrivals = list.filter((r) => r.status === 'Confirmed' && r.checkInDate <= t).length
  const departures = list.filter((r) => r.status === 'CheckedIn' && r.checkOutDate <= t).length
  const scope = isRecep
    ? (branches.data?.find((b) => b.branchId === branchId)?.name ?? '')
    : 'Toàn hệ thống'

  const todo = [
    { to: '/dashboard/reservations', n: arrivals, label: 'Khách chờ nhận phòng' },
    { to: '/dashboard/reservations', n: departures, label: 'Khách đến hạn trả phòng' },
    { to: '/dashboard/rooms', n: count('Dirty'), label: 'Phòng cần dọn' },
  ]

  return (
    <>
      <h1 className="ov-hello">Xin chào, {user?.fullName}</h1>
      <p className="ov-sub">
        {new Date().toLocaleDateString('vi-VN', { weekday: 'long', day: 'numeric', month: 'numeric', year: 'numeric' })}
        {' · '}{scope}
      </p>

      <div className="ov-heading"><h2>Hoạt động hiện tại</h2><Link to="/dashboard/rooms">Xem sơ đồ phòng →</Link></div>
      {rooms.isLoading && <p role="status">Đang tải tình trạng phòng...</p>}
      {rooms.error && <p className="err" role="alert">Không tải được tình trạng phòng.</p>}
      {!rooms.isLoading && !rooms.error && <div className="grid ov-metrics">
        <div className="card stat"><span className="dot Available" /> Phòng sẵn sàng<div className="price">{count('Available')}</div></div>
        <div className="card stat"><span className="dot Occupied" /> Đang có khách<div className="price">{count('Occupied')}</div></div>
        <div className="card stat"><span className="dot Dirty" /> Cần dọn<div className="price">{count('Dirty')}</div></div>
        <div className="card stat">Công suất hiện tại<div className="price">{occupancy}%</div></div>
      </div>}

      {!isRecep && report.data && (
        <p className="mut">
          Doanh thu hôm nay: <b>{vnd(report.data.revenueToday)}</b> · {report.data.newBookings} đơn,
          {' '}{report.data.cancelled} đơn đã hủy
        </p>
      )}

      <div className="ov-heading"><h2>Cần xử lý hôm nay</h2><Link to="/dashboard/reservations">Xem đơn đặt phòng →</Link></div>
      {res.isLoading && <p role="status">Đang tải đơn đặt phòng...</p>}
      {res.error && <p className="err" role="alert">Không tải được đơn đặt phòng.</p>}
      {!res.isLoading && !res.error && <div className="todo">
        {todo.map((x) => (
          <Link key={x.label} to={x.to} className={`todo-card ${x.n === 0 ? 'zero' : ''}`}>
            <b>{x.n}</b>
            <span>{x.label}</span>
            <em>{x.n === 0 ? 'Không có việc tồn đọng' : 'Xử lý ngay →'}</em>
          </Link>
        ))}
      </div>}
    </>
  )
}
