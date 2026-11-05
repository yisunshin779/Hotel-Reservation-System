import { Navigate, Route, Routes } from 'react-router-dom'
import PublicLayout from './layouts/PublicLayout'
import DashboardLayout from './layouts/DashboardLayout'
import RequireRole from './components/RequireRole'
import SearchPage from './pages/SearchPage'
import LoginPage from './pages/LoginPage'
import MyBookingsPage from './pages/MyBookingsPage'
import RoomMapPage from './pages/dashboard/RoomMapPage'
import ReservationsPage from './pages/dashboard/ReservationsPage'
import ReportsPage from './pages/dashboard/ReportsPage'
import BookingPage from './BookingPage'
import PaymentPage from './PaymentPage'

export default function App() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route index element={<SearchPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route element={<RequireRole roles={['guest']} />}>
            <Route path="book" element={<BookingPage />} />
            <Route path="pay/:id" element={<PaymentPage />} />
            <Route path="my-bookings" element={<MyBookingsPage />} />
        </Route>
      </Route>

      <Route element={<RequireRole roles={['receptionist', 'manager', 'admin']} />}>
        <Route path="dashboard" element={<DashboardLayout />}>
          <Route index element={<Navigate to="rooms" replace />} />
          <Route path="rooms" element={<RoomMapPage />} />
          <Route path="reservations" element={<ReservationsPage />} />
          <Route element={<RequireRole roles={['manager', 'admin']} />}>
            <Route path="reports" element={<ReportsPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>

  
  )
}