import { Navigate, Route, Routes } from 'react-router-dom'
import PublicLayout from './layouts/PublicLayout'
import DashboardLayout from './layouts/DashboardLayout'
import SearchPage from './pages/SearchPage'
import LoginPage from './pages/LoginPage'
import MyBookingsPage from './pages/MyBookingsPage'
import RoomMapPage from './pages/dashboard/RoomMapPage'
import ReservationsPage from './pages/dashboard/ReservationsPage'
import ReportsPage from './pages/dashboard/ReportsPage'

export default function App() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route index element={<SearchPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="my-bookings" element={<MyBookingsPage />} />
      </Route>

      <Route path="dashboard" element={<DashboardLayout />}>
        <Route index element={<Navigate to="rooms" replace />} />
        <Route path="rooms" element={<RoomMapPage />} />
        <Route path="reservations" element={<ReservationsPage />} />
        <Route path="reports" element={<ReportsPage />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}