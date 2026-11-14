import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../store/auth'
import Dropdown from '../components/Dropdown'
import LangSwitch from '../components/LangSwitch'
import TopProgress from '../components/TopProgress'

export default function PublicLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const staff = !!user && user.role !== 'guest'

  return (
    <>
      <TopProgress />
      <header className="top">
        <Link
          to="/"
          className="logo"
          onClick={() => {
            if (pathname === '/') window.scrollTo({ top: 0, behavior: 'smooth' })
          }}
        >
          <span className="logo-mark">S</span>
          Sứ Hotel
        </Link>

        <nav>
          <Dropdown label="Khám phá">
            <Link to="/about"><b>Về Sứ Hotel</b><small>Câu chuyện và giá trị</small></Link>
            <Link to="/branches"><b>Chi nhánh</b><small>Hà Nội, Đà Nẵng, TP.HCM</small></Link>
            <Link to="/rooms"><b>Hạng phòng</b><small>Standard, Deluxe, Suite</small></Link>
          </Dropdown>
          <NavLink to="/search">Tìm phòng</NavLink>
        </nav>

        {user ? (
          <Dropdown
            align="right"
            label={
              <>
                <span className="avatar-sm">{user.fullName.trim().charAt(0).toUpperCase()}</span>
                <span className="uname">{user.fullName}</span>
              </>
            }
          >
            <div className="dd-head"><b>{user.fullName}</b><small>{user.email}</small></div>
            <div className="dd-sep" />
            <Link to="/profile">Hồ sơ cá nhân</Link>
            {user.role === 'guest' && <Link to="/my-bookings">Đơn của tôi</Link>}
            {staff && <Link to="/dashboard">Trang quản lý</Link>}
            <div className="dd-sep" />
            <button type="button" onClick={() => { logout(); navigate('/') }}>Đăng xuất</button>
          </Dropdown>
        ) : (
          <>
            <Link to="/login" className="btn">Đăng nhập</Link>
            <Link to="/register" className="btn primary">Đăng ký</Link>
          </>
        )}
      </header>

      <main className="page"><Outlet /></main>

      <footer className="site-foot">
        <div className="foot-in">
          <div>
            <div className="logo"><span className="logo-mark">S</span>Sứ Hotel</div>
            <p>Hệ thống khách sạn ba chi nhánh, đặt phòng nhanh chóng và giá rõ ràng.</p>
          </div>
          <div>
            <h4>Khám phá</h4>
            <Link to="/about">Về Sứ Hotel</Link>
            <Link to="/branches">Chi nhánh</Link>
            <Link to="/rooms">Hạng phòng</Link>
          </div>
          <div>
            <h4>Đặt phòng</h4>
            <Link to="/search">Tìm phòng</Link>
            <Link to="/login">Đăng nhập</Link>
            <Link to="/register">Đăng ký</Link>
          </div>
          <div>
            <h4>Ngôn ngữ</h4>
            <LangSwitch />
          </div>
        </div>
        <div className="foot-copy">© 2026 Sứ Hotel · Đồ án Phân tích thiết kế hệ thống</div>
      </footer>
    </>
  )
}