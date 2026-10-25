import { Link, NavLink, Outlet } from 'react-router-dom'

export default function PublicLayout() {
  return (
    <>
      <header className="top">
        <Link to="/" className="logo">🏨 Sứ Hotel</Link>
        <nav>
          <NavLink to="/">Tìm phòng</NavLink>
          <NavLink to="/my-bookings">Đơn của tôi</NavLink>
        </nav>
        <Link to="/login" className="btn primary">Đăng nhập</Link>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </>
  )
}