import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../store/auth'
import type { Role } from '../types'

export default function RequireRole({ roles }: { roles: Role[] }) {
  const user = useAuth((s) => s.user)
  const location = useLocation()

  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />
  }
  if (!roles.includes(user.role)) {
    return <Navigate to={user.role === 'guest' ? '/' : '/dashboard'} replace />
  }
  return <Outlet />
}