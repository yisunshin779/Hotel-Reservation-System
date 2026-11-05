import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../store/auth'

export default function DashboardLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const canReport = user?.role === 'manager' || user?.role === 'admin'

  return (
    <div className="dash">
      <aside>
        <div className="logo">🏨 HRS Dashboard</div>
        <NavLink to="/dashboard/rooms">Sơ đồ phòng</NavLink>
        <NavLink to="/dashboard/reservations">Đơn đặt phòng</NavLink>
        {canReport && <NavLink to="/dashboard/reports">Báo cáo</NavLink>}
        <div style={{ flex: 1 }} />
        <small>{user?.fullName}</small>
        <button className="btn" onClick={() => { logout(); navigate('/login') }}>Đăng xuất</button>
      </aside>
      <main className="page"><Outlet /></main>
    </div>
  )
}