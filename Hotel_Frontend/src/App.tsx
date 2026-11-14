import { Navigate, Route, Routes } from 'react-router-dom'
import PublicLayout from './layouts/PublicLayout'
import DashboardLayout from './layouts/DashboardLayout'
import RequireRole from './components/RequireRole'
import HomePage from './pages/HomePage'
import AboutPage from './pages/AboutExperiencePage'
import BranchesPage from './pages/BranchesPage'
import RoomsPage from './pages/RoomsPage'
import RoomDetailPage from './pages/RoomDetailPage'
import SearchPage from './pages/SearchPage'
import GuestGuidePage from './pages/GuestGuidePage'
import AmenitiesPage from './pages/AmenitiesPage'
import AmenityDetailPage from './pages/AmenityDetailPage'
import AmenityBookingPage from './pages/AmenityBookingPage'
import MyAmenitiesPage from './pages/MyAmenitiesPage'
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
import ReviewsPage from './pages/dashboard/ReviewsPage'
import OverviewPage from './pages/dashboard/OverviewPage'

export default function App() {
  return (
    <Routes>
      <Route element={<PublicLayout />}>
        <Route index element={<HomePage />} />
        <Route path="about" element={<AboutPage />} />
        <Route path="branches" element={<BranchesPage />} />
        <Route path="rooms" element={<RoomsPage />} />
        <Route path="rooms/:id" element={<RoomDetailPage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="guide" element={<GuestGuidePage />} />
        <Route path="amenities" element={<AmenitiesPage />} />
        <Route path="amenities/:id" element={<AmenityDetailPage />} />
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
          <Route path="amenities/:id/book" element={<AmenityBookingPage />} />
          <Route path="my-amenities" element={<MyAmenitiesPage />} />
        </Route>
      </Route>

      <Route element={<RequireRole roles={['receptionist', 'manager', 'admin']} />}>
        <Route path="dashboard" element={<DashboardLayout />}>
          <Route index element={<OverviewPage />} />
          <Route path="rooms" element={<RoomMapPage />} />
          <Route path="reservations" element={<ReservationsPage />} />

          <Route element={<RequireRole roles={['manager', 'admin']} />}>
            <Route path="reports" element={<ReportsPage />} />
            <Route path="pricing" element={<PricingPage />} />
            <Route path="reviews" element={<ReviewsPage />} />
          </Route>
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
