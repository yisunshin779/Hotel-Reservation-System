import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../store/auth'
import LangSwitch from '../components/LangSwitch'

export default function PublicLayout() {
  const { t } = useTranslation()
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()

  return (
    <>
      <header className="top">
        <Link
          to="/"
          className="logo"
          onClick={() => {
            if (pathname === '/') window.scrollTo({ top: 0, behavior: 'smooth' })
          }}
        >
          🏨 Sứ Hotel
        </Link>
        <nav>
          <NavLink to="/" end>{t('nav.about')}</NavLink>
          <NavLink to="/search">{t('nav.search')}</NavLink>
          {user?.role === 'guest' && <NavLink to="/my-bookings">{t('nav.myBookings')}</NavLink>}
          {user && user.role !== 'guest' && <NavLink to="/dashboard">{t('Trang quản lý')}</NavLink>}
        </nav>
        <LangSwitch />
        {user ? (
          <>
            <Link to="/profile" className="user-chip">👤 {user.fullName}</Link>
            <button className="btn" onClick={() => { logout(); navigate('/') }}>{t('nav.logout')}</button>
          </>
        ) : (
          <Link to="/login" className="btn primary">{t('nav.login')}</Link>
        )}
      </header>
      <main className="page"><Outlet /></main>
    </>
  )
}