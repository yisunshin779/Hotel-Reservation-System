export type Role = "guest" | "receptionist" | "manager" | "admin";
export type ReservationStatus =
  | "Pending" | "Confirmed" | "CheckedIn" | "CheckedOut"
  | "Cancelled" | "Expired" | "NoShow";
export type RoomStatus = "Available" | "Occupied" | "Dirty" | "Maintenance";

export interface User {
  id: string;
  fullName: string;
  email: string;
  role: Role;
  branchId?: string;
}

export interface Branch {
  branchId: string;
  name: string;
  address: string;
}

export interface RoomType {
  roomTypeId: string;
  name: string;
  capacity: number;
  basePrice: number;
  description: string;
  amenities: string[];
  images: string[];
}

export interface Room {
  roomId: string;
  branchId: string;
  roomNumber: string;
  status: RoomStatus;
  roomTypeName: string;
}

export interface Reservation {
  reservationId: string;
  code: string;
  guestName: string;
  roomTypeName: string;
  checkInDate: string;
  checkOutDate: string;
  totalAmount: number;
  paidAmount: number;
  status: ReservationStatus;
  holdExpiresAt?: string;
  roomNumber?: string
  branchId?: string
}

export interface SearchParams {
  branchId: string
  checkIn: string
  checkOut: string
  adults: number
  children: number
  rooms: number
}

export interface Availability {
  roomType: RoomType
  available: number
  total: number
}

export interface CreateReservation {
  branchId: string
  roomTypeId: string
  quantity: number
  checkIn: string
  checkOut: string
  guestName: string
  phone: string
  note?: string
  promoCode?: string
}

export interface RevenuePoint {
  date: string
  revenue: number
}

export interface ReportSummary {
  revenueToday: number
  occupancy: number // phần trăm, 0-100
  newBookings: number
  cancelled: number
  series: RevenuePoint[]
}

export interface Promotion {
  code: string
  percent: number
  validTo: string
}

export interface PriceRow {
  roomTypeId: string
  name: string
  normal: number
  weekend: number
  holiday: number
}