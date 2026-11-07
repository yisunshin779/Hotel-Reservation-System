import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../store/auth'
import LangSwitch from '../components/LangSwitch'

export default function PublicLayout() {
  const { t } = useTranslation()
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <>
      <header className="top">
        <Link to="/" className="logo">🏨 Sứ Hotel</Link>
        <nav>
          <NavLink to="/">{t('nav.search')}</NavLink>
          {user?.role === 'guest' && <NavLink to="/my-bookings">{t('nav.myBookings')}</NavLink>}
        </nav>
        <LangSwitch />
        {user ? (
          <button className="btn" onClick={() => { logout(); navigate('/') }}>{t('nav.logout')}</button>
        ) : (
          <Link to="/login" className="btn primary">{t('nav.login')}</Link>
        )}
      </header>
      <main className="page"><Outlet /></main>
    </>
  )
}