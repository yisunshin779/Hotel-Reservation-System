import { Link, useParams } from 'react-router-dom'
import PageHead from '../components/PageHead'
import { AmenityApi } from '../api/amenities'
import { useAuth } from '../store/auth'

export default function AmenityDetailPage() {
  const { id = '' } = useParams()
  const item = AmenityApi.get(id)
  const user = useAuth((state) => state.user)
  if (!item) return <div className="card"><h1>Không tìm thấy dịch vụ</h1><Link to="/amenities">Xem dịch vụ khác →</Link></div>
  return <div className="amenities-page">
    <PageHead title={item.name} sub={item.category} crumbs={[{ to: '/', label: 'Trang chủ' }, { to: '/amenities', label: 'Dịch vụ tiện ích' }, { label: item.name }]} />
    <div className="amenity-detail">
      <div className="amenity-detail-photo"><img src={item.image} alt={`Ảnh minh họa ${item.name}`} /></div>
      <div className="amenity-detail-copy"><span className="about-eyebrow">Trải nghiệm tại Sứ Hotel</span><h2>{item.name}</h2><p>{item.description}</p>
        <h3>Thông tin cần biết</h3><ul>{item.details.map((detail) => <li key={detail}>{detail}</li>)}</ul>
        <p><strong>Giờ phục vụ:</strong> {item.hours}</p>
        <Link className="btn primary" to={user?.role === 'guest' ? `/amenities/${id}/book` : '/login'} state={user ? undefined : { from: `/amenities/${id}/book` }}>
          {user?.role === 'guest' ? 'Gửi yêu cầu đặt lịch' : 'Đăng nhập để đặt lịch'}
        </Link>
        <p className="amenity-note">Yêu cầu đặt lịch hiện chỉ lưu trong trình duyệt và chưa được khách sạn xác nhận.</p>
      </div>
    </div>
  </div>
}
