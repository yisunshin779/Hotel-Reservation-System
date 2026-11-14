import { Link } from 'react-router-dom'
import PageHead from '../components/PageHead'
import Icon from '../components/Icon'

const BRANCHES = [
  {
    id: 'hn', name: 'Hà Nội – Hoàn Kiếm', image: '/branch-hanoi.png', landmark: 'Hồ Hoàn Kiếm', rooms: 60,
    address: '12 Hàng Bài, Hoàn Kiếm, Hà Nội', phone: '024 3825 0001',
    desc: 'Nằm giữa phố cổ, bước chân ra là hồ Gươm và những quán cà phê trứng nổi tiếng.',
    points: ['Cách hồ Hoàn Kiếm 5 phút đi bộ', 'Gần phố ẩm thực và Nhà hát lớn', 'Thuận tiện ra sân bay Nội Bài'],
  },
  {
    id: 'dn', name: 'Đà Nẵng – Mỹ Khê', image: '/branch-danang.png', landmark: 'Cầu Rồng', rooms: 60,
    address: '88 Võ Nguyên Giáp, Sơn Trà, Đà Nẵng', phone: '0236 3912 002',
    desc: 'Cách biển vài phút đi bộ, đón bình minh trên một trong những bãi biển đẹp nhất hành tinh.',
    points: ['Cách biển Mỹ Khê 300 m', 'Gần cầu Rồng và bán đảo Sơn Trà', 'Cách sân bay Đà Nẵng 15 phút'],
  },
  {
    id: 'hcm', name: 'TP.HCM – Quận 1', image: '/branch-saigon.png', landmark: 'Bưu điện Trung tâm Sài Gòn', rooms: 60,
    address: '45 Lê Lợi, Quận 1, TP.HCM', phone: '028 3822 0003',
    desc: 'Giữa trung tâm sôi động, thuận tiện đi lại, ăn uống và mua sắm cả ngày lẫn đêm.',
    points: ['Gần chợ Bến Thành và phố đi bộ Nguyễn Huệ', 'Nhiều nhà hàng, quán cà phê xung quanh', 'Cách sân bay Tân Sơn Nhất 25 phút'],
  },
]

export default function BranchesPage() {
  return (
    <>
      <PageHead
        title="Chi nhánh"
        sub="Ba chi nhánh với cùng một tiêu chuẩn phục vụ. Chọn nơi bạn muốn đến để xem phòng trống."
        crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Chi nhánh' }]}
      />
      <div className="bgrid branch-grid motion-stagger">
        {BRANCHES.map((b) => (
          <article className="bcard branch-card" key={b.id}>
            <div className="branch-photo">
              <img src={b.image} alt={`Ảnh minh họa ${b.landmark}`} loading="lazy" />
              <span className="branch-landmark"><Icon name="pin" size={14} />{b.landmark}</span>
            </div>
            <div className="bbody">
              <div className="branch-card-heading"><span>CHI NHÁNH SỨ HOTEL</span><h2>{b.name}</h2></div>
              <p>{b.desc}</p>
              <div className="binfo">
                <div><Icon name="pin" size={16} />{b.address}</div>
                <div><Icon name="phone" size={16} />{b.phone}</div>
                <div><Icon name="building" size={16} />{b.rooms} phòng, 5 tầng</div>
              </div>
              <ul className="bpoints">
                {b.points.map((p) => <li key={p}><Icon name="check" size={16} />{p}</li>)}
              </ul>
              <Link className="btn primary branch-cta" to="/search" state={{ branchId: b.id }}>Xem phòng trống <span aria-hidden="true">→</span></Link>
            </div>
          </article>
        ))}
      </div>
    </>
  )
}
