import { useLocation, Link } from 'react-router-dom'
import { useState } from 'react'
import { AmenityApi } from '../api/amenities'
import { useAuth } from '../store/auth'
import PageHead from '../components/PageHead'

export default function MyAmenitiesPage() {
  const user = useAuth((state) => state.user)!
  const created = (useLocation().state as { created?: boolean } | null)?.created
  const [list, setList] = useState(() => AmenityApi.mine(user.id))
  function cancel(id: string) {
    AmenityApi.cancel(id, user.id)
    setList(AmenityApi.mine(user.id))
  }
  return <div className="amenities-page">
    <PageHead title="Lịch dịch vụ của tôi" sub="Theo dõi các yêu cầu đặt lịch tiện ích trên trình duyệt này."
      crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Lịch dịch vụ của tôi' }]} />
    {created && <p className="ok-msg" role="status">Đã lưu yêu cầu đặt lịch trên trình duyệt. Khách sạn chưa xác nhận lịch này.</p>}
    {list.length === 0 ? <div className="card">Bạn chưa có yêu cầu đặt lịch nào. <Link to="/amenities">Khám phá dịch vụ →</Link></div> :
      <div className="amenity-bookings">{list.map((booking) => {
        const item = AmenityApi.get(booking.amenityId)
        return <article className="card" key={booking.id}>
          <div><span className="about-eyebrow">Yêu cầu demo · Chưa xác nhận</span><h2>{item?.name ?? 'Dịch vụ'}</h2>
            <p>{booking.date} · {booking.time} · {booking.guests} người</p>{booking.note && <p>Ghi chú: {booking.note}</p>}</div>
          <button className="btn" onClick={() => cancel(booking.id)}>Xóa yêu cầu</button>
        </article>
      })}</div>}
    <p className="amenity-note">Các yêu cầu chỉ nằm trên thiết bị này, chưa được chuyển tới khách sạn hoặc đồng bộ sang thiết bị khác.</p>
  </div>
}
