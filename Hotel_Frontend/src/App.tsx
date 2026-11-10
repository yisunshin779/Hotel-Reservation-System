import { Navigate, Route, Routes } from 'react-router-dom'
import PublicLayout from './layouts/PublicLayout'
import DashboardLayout from './layouts/DashboardLayout'
import RequireRole from './components/RequireRole'
import AboutPage from './pages/AboutPage'
import SearchPage from './pages/SearchPage'
import RoomDetailPage from './pages/RoomDetailPage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'
import ForgotPasswordPage from './pages/ForgotPasswordPage'
import ProfilePage from './pages/ProfilePage'
import BookingPage from './pages/BookingPage'
import PaymentPage from './pages/PaymentPage'
import MyBookingsPage from './pages/MyBookingsPage'
import RoomMapPage from './pages/dashboard/RoomMapPage'
import ReservationsPage from './pages/dashboard/ReservationsPage'
import ReportsPage from './pages/dashboard/ReportsPage'
import PricingPage from './pages/dashboard/PricingPage'

export default function App() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route index element={<AboutPage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="rooms/:id" element={<RoomDetailPage />} />
        <Route path="about" element={<Navigate to="/" replace />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />

        <Route element={<RequireRole roles={['guest', 'receptionist', 'manager', 'admin']} />}>
          <Route path="profile" element={<ProfilePage />} />
        </Route>

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
            <Route path="pricing" element={<PricingPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}