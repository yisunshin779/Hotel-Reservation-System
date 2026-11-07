import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { useAuth } from '../store/auth'
import LangSwitch from '../components/LangSwitch'

export default function DashboardLayout() {
  const { t } = useTranslation()
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const canReport = user?.role === 'manager' || user?.role === 'admin'

  return (
    <div className="dash">
      <aside>
        <div className="logo">🏨 HRS Dashboard</div>
        <NavLink to="/dashboard/rooms">{t('dash.rooms')}</NavLink>
        <NavLink to="/dashboard/reservations">{t('dash.reservations')}</NavLink>
        {canReport && <NavLink to="/dashboard/reports">{t('dash.reports')}</NavLink>}
        {canReport && <NavLink to="/dashboard/pricing">{t('dash.pricing')}</NavLink>}
        <div style={{ flex: 1 }} />
        <small>{user?.fullName}</small>
        <LangSwitch />
        <button className="btn" onClick={() => { logout(); navigate('/login') }}>{t('nav.logout')}</button>
      </aside>
      <main className="page"><Outlet /></main>
    </div>
  )
}