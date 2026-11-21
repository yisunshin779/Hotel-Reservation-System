import { useState } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../store/auth'
import PageTransition from '../components/PageTransition'
import TopProgress from '../components/TopProgress'

const ROLE_LABEL: Record<string, string> = {
  receptionist: 'Lễ tân',
  manager: 'Quản lý',
  admin: 'Quản trị viên',
  guest: 'Khách hàng',
}

const GROUPS = [
  {
    title: 'Vận hành khách sạn', adminOnly: false,
    items: [
      { to: '/dashboard', label: 'Tổng quan', end: true },
      { to: '/dashboard/rooms', label: 'Sơ đồ phòng' },
      { to: '/dashboard/reservations', label: 'Đơn đặt phòng' },
    ],
  },
  {
    title: 'Quản trị', adminOnly: true,
    items: [
      { to: '/dashboard/reports', label: 'Báo cáo' },
      { to: '/dashboard/pricing', label: 'Giá & khuyến mãi' },
      { to: '/dashboard/reviews', label: 'Đánh giá' },
    ],
  },
]

const TITLES: Record<string, string> = {
  '/dashboard': 'Tổng quan',
  '/dashboard/rooms': 'Sơ đồ phòng',
  '/dashboard/reservations': 'Đơn đặt phòng',
  '/dashboard/reports': 'Báo cáo',
  '/dashboard/pricing': 'Giá & khuyến mãi',
  '/dashboard/reviews': 'Đánh giá',
}

export default function DashboardLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [open, setOpen] = useState(false)
  const canManage = user?.role === 'manager' || user?.role === 'admin'

  return (
    <div className="dsh">
    <TopProgress />
      <div className={`dsh-scrim ${open ? 'show' : ''}`} onClick={() => setOpen(false)} />

      <aside className={`dsh-side ${open ? 'open' : ''}`}>
        <Link to="/dashboard" className="dsh-brand">
          <span className="dsh-mark">S</span>
          Sứ Hotel
          <small>Quản lý</small>
        </Link>

        {GROUPS.filter((g) => !g.adminOnly || canManage).map((g) => (
          <div key={g.title}>
            <div className="dsh-group">{g.title}</div>
            <nav>
              {g.items.map((i) => (
                <NavLink key={i.to} to={i.to} end={i.end} onClick={() => setOpen(false)}>{i.label}</NavLink>
              ))}
            </nav>
          </div>
        ))}

        <div className="dsh-spacer" />

        <div className="dsh-user">
          <div className="dsh-user-row">
            <span className="dsh-avatar">{user?.fullName.trim().charAt(0).toUpperCase()}</span>
            <div>
              <b>{user?.fullName}</b>
              <small>{ROLE_LABEL[user?.role ?? 'guest']}</small>
            </div>
          </div>
          <Link to="/profile" className="btn" onClick={() => setOpen(false)}>Hồ sơ cá nhân</Link>
          <button className="btn" onClick={() => { logout(); navigate('/login') }}>Đăng xuất</button>
        </div>
      </aside>

      <div className="dsh-body">
        <header className="dsh-top">
          <button className="dsh-burger" onClick={() => setOpen(true)} aria-label="Mở menu">☰</button>
          <span className="dsh-crumb">{TITLES[pathname] ?? 'Quản lý'}</span>
          <Link to="/" className="btn">← Trang khách</Link>
        </header>
        <main className="dsh-main"><PageTransition /></main>
      </div>
    </div>
  )
}
