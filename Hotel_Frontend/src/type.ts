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
}