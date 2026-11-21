import { Link } from 'react-router-dom'
import PageHead from '../components/PageHead'
import { AmenityApi } from '../api/amenities'

export default function AmenitiesPage() {
  return <div className="amenities-page">
    <PageHead title="Dịch vụ tiện ích" sub="Khám phá các trải nghiệm có thể lên lịch trong chuyến đi. Danh mục hiện là bản giới thiệu demo."
      crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Dịch vụ tiện ích' }]} />
    <div className="amenity-grid">{AmenityApi.list().map((item) => <article className="amenity-card" key={item.id}>
      <Link to={`/amenities/${item.id}`} className="amenity-card-image"><img src={item.image} alt="" loading="lazy" /></Link>
      <div className="amenity-card-body"><span>{item.category}</span><h2>{item.name}</h2><p>{item.description}</p>
        <Link to={`/amenities/${item.id}`} className="btn">Xem chi tiết →</Link></div>
    </article>)}</div>
    <p className="amenity-note">Các tiện ích, giờ phục vụ và khả năng nhận lịch cần được xác nhận với từng chi nhánh.</p>
  </div>
}
