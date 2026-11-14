import { Link } from 'react-router-dom'
import PageHead from '../components/PageHead'

const VALUES = [
  { number: '01', title: 'Dễ chọn phòng', text: 'Xem hạng phòng, sức chứa và giá theo ngày trước khi quyết định.' },
  { number: '02', title: 'Dễ theo dõi', text: 'Thông tin đặt phòng và trạng thái đơn được tập trung trong tài khoản của bạn.' },
  { number: '03', title: 'Được hỗ trợ trong kỳ ở', text: 'Lễ tân tiếp nhận nhu cầu lưu trú và các dịch vụ phát sinh tại khách sạn.' },
]

const STEPS = [
  { title: 'Chọn điểm đến', text: 'Khám phá các chi nhánh tại Hà Nội, Đà Nẵng và TP.HCM.', to: '/branches', action: 'Xem chi nhánh' },
  { title: 'Tìm phòng phù hợp', text: 'Chọn ngày ở, số khách và hạng phòng theo nhu cầu.', to: '/search', action: 'Tìm phòng' },
  { title: 'Quản lý chuyến đi', text: 'Theo dõi đơn đặt phòng và các thông tin cần thiết trong tài khoản.', to: '/login', action: 'Đến tài khoản' },
]

export default function AboutExperiencePage() {
  return (
    <div className="about-page">
      <PageHead title="Về Sứ Hotel" sub="Một nơi nghỉ chân ấm áp, dễ đặt phòng và dễ lên kế hoạch cho chuyến đi."
        crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Về Sứ Hotel' }]} />

      <section className="about-intro">
        <div className="about-intro-copy">
          <span className="about-eyebrow">Câu chuyện của chúng tôi</span>
          <h2>Sự giản dị tạo nên cảm giác an tâm.</h2>
          <p>Sứ Hotel lấy cảm hứng từ gốm sứ Việt: gần gũi, bền bỉ và chăm chút trong từng chi tiết. Chúng tôi muốn việc tìm một chỗ nghỉ phù hợp trở nên rõ ràng và thuận tiện.</p>
          <p>Từ lúc chọn điểm đến đến khi hoàn tất kỳ ở, khách có thể xem thông tin phòng và quản lý đơn trên cùng một hệ thống. Đội ngũ tại chi nhánh hỗ trợ các nhu cầu phát sinh trong thời gian lưu trú.</p>
          <Link to="/branches" className="btn primary">Khám phá chi nhánh</Link>
        </div>
        <div className="about-visual">
          <div className="about-visual-orbit" aria-hidden="true" />
          <div className="about-image">
            <div className="about-image-photo" role="img" aria-label="Không gian khách sạn" />
            <div className="about-image-shine" aria-hidden="true" />
            <div className="about-image-caption"><span>SỨ HOTEL</span><strong>Mỗi hành trình cần một nơi để trở về.</strong></div>
          </div>
          <div className="about-visual-note" aria-hidden="true"><span>✦</span> Ấm áp trong từng chi tiết</div>
        </div>
      </section>

      <section className="about-section" aria-labelledby="about-values">
        <span className="about-eyebrow">Điều chúng tôi hướng đến</span>
        <h2 id="about-values">Một trải nghiệm rõ ràng ở mỗi bước</h2>
        <div className="about-cards">
          {VALUES.map((value) => <article className="about-card" key={value.number}>
            <span>{value.number}</span><h3>{value.title}</h3><p>{value.text}</p>
          </article>)}
        </div>
      </section>

      <section className="about-section" aria-labelledby="about-journey">
        <span className="about-eyebrow">Hành trình của bạn</span>
        <h2 id="about-journey">Bắt đầu từ điều bạn cần</h2>
        <div className="about-steps">
          {STEPS.map((step, index) => <article className="about-step" key={step.title}>
            <b>0{index + 1}</b><div><h3>{step.title}</h3><p>{step.text}</p></div>
            <Link to={step.to}>{step.action} →</Link>
          </article>)}
        </div>
      </section>

      <section className="about-callout">
        <div><span className="about-eyebrow">Sứ Hotel</span><h2>Sẵn sàng cho chuyến đi tiếp theo?</h2><p>Chọn ngày, chọn chi nhánh và xem phòng phù hợp với bạn.</p></div>
        <Link to="/search" className="btn primary">Tìm phòng trống →</Link>
      </section>
    </div>
  )
}
