import { NavLink, Outlet } from 'react-router-dom'

export default function DashboardLayout() {
  return (
    <div className="dash">
      <aside>
        <div className="logo">🏨 HRS Dashboard</div>
        <NavLink to="/dashboard/rooms">Sơ đồ phòng</NavLink>
        <NavLink to="/dashboard/reservations">Đơn đặt phòng</NavLink>
        <NavLink to="/dashboard/reports">Báo cáo</NavLink>
      </aside>
      <main className="page">
        <Outlet />
      </main>
    </div>
  )
}