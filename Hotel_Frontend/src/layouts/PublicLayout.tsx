import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../store/auth'

export default function PublicLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <>
      <header className="top">
        <Link to="/" className="logo">🏨 Sứ Hotel</Link>
        <nav>
          <NavLink to="/">Tìm phòng</NavLink>
          {user?.role === 'guest' && <NavLink to="/my-bookings">Đơn của tôi</NavLink>}
        </nav>
        {user ? (
          <button className="btn" onClick={() => { logout(); navigate('/') }}>Đăng xuất</button>
        ) : (
          <Link to="/login" className="btn primary">Đăng nhập</Link>
        )}
      </header>
      <main className="page"><Outlet /></main>
    </>
  )
}