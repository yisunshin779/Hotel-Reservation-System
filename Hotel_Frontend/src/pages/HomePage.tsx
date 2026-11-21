import { Link } from 'react-router-dom'
import HeroFx from '../components/HeroFx'
import Icon from '../components/Icon'

const EXPLORE = [
  { to: '/branches', icon: 'building', title: 'Chi nhánh', text: 'Ba điểm đến tại Hà Nội, Đà Nẵng và TP.HCM, mỗi nơi một phong cách.', more: 'Xem các chi nhánh' },
  { to: '/rooms', icon: 'bed', title: 'Hạng phòng', text: 'Từ Standard gọn gàng đến Suite có phòng khách riêng.', more: 'Xem các hạng phòng' },
  { to: '/about', icon: 'info', title: 'Về Sứ Hotel', text: 'Câu chuyện, giá trị và nhận xét từ khách đã lưu trú.', more: 'Tìm hiểu thêm' },
]

export default function HomePage() {
  return (
    <div className="home">
      <section className="home-hero hero-photo hero-about hero-lg">
        <HeroFx />
        <div className="hero-text">
          <span className="badge-top">Hệ thống khách sạn Sứ Hotel</span>
          <h1>Nơi nghỉ ngơi trọn vẹn<br />cho mỗi chuyến đi</h1>
          <p>Đặt phòng nhanh chóng, giá rõ ràng, xác nhận ngay.</p>
          <div className="hero-cta">
            <Link to="/search" className="btn primary big">Tìm phòng trống</Link>
            <Link to="/branches" className="btn ghost-light big">Xem chi nhánh</Link>
          </div>
        </div>
        <svg className="wave" viewBox="0 0 1200 80" preserveAspectRatio="none" aria-hidden="true">
          <path d="M0,40 C200,90 400,0 600,40 C800,80 1000,10 1200,40 L1200,80 L0,80 Z" fill="#fff8ef" />
        </svg>
      </section>

      <div className="home-body">
        <h2 className="section-title">Khám phá Sứ Hotel</h2>
        <div className="explore">
          {EXPLORE.map((e) => (
            <Link to={e.to} className="ecard" key={e.to}>
              <div className="ico"><Icon name={e.icon} size={24} /></div>
              <h3>{e.title}</h3>
              <p>{e.text}</p>
              <span className="more">{e.more} →</span>
            </Link>
          ))}
        </div>

        <div className="cta">
          <h3>Sẵn sàng cho chuyến đi tiếp theo?</h3>
          <p>Chọn chi nhánh, chọn ngày và xác nhận phòng chỉ trong vài phút.</p>
          <Link to="/search" className="btn primary">Tìm phòng trống</Link>
        </div>
      </div>
    </div>
  )
}