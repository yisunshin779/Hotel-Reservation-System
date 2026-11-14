import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import PageHead from '../components/PageHead'
import Icon from '../components/Icon'

// Số chạy từ 0 đến giá trị đích khi cuộn tới
function Counter({ to, decimals = 0, suffix = '' }: { to: number; decimals?: number; suffix?: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [val, setVal] = useState(0)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    let raf = 0
    const obs = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting) return
      obs.disconnect()
      const start = performance.now()
      const tick = (now: number) => {
        const k = Math.min(1, (now - start) / 1500)
        setVal(to * (1 - Math.pow(1 - k, 3)))
        if (k < 1) raf = requestAnimationFrame(tick)
      }
      raf = requestAnimationFrame(tick)
    })
    obs.observe(el)
    return () => { obs.disconnect(); cancelAnimationFrame(raf) }
  }, [to])

  return <span ref={ref}>{Number(val.toFixed(decimals)).toLocaleString('vi-VN')}{suffix}</span>
}

const STATS = [
  { to: 3, label: 'Chi nhánh', suffix: '' },
  { to: 180, label: 'Phòng nghỉ', suffix: '' },
  { to: 10000, label: 'Khách đã đặt phòng', suffix: '+' },
  { to: 4.8, label: 'Điểm đánh giá', suffix: '/5', decimals: 1 },
]

const VALUES = [
  { icon: 'check', title: 'Phòng trống thời gian thực', text: 'Xem ngay phòng còn trống và giá chính xác theo ngày bạn chọn.' },
  { icon: 'check', title: 'Hủy miễn phí', text: 'Được hủy hoặc đổi đơn miễn phí trước 48 giờ nhận phòng.' },
  { icon: 'check', title: 'Ưu đãi thành viên', text: 'Nhập mã khuyến mãi khi đặt phòng để được giảm giá ngay.' },
]

const QUOTES = [
  { text: 'Phòng sạch sẽ, nhân viên thân thiện, đặt phòng chỉ mất vài phút.', who: 'Minh Anh, Hà Nội' },
  { text: 'Giá rõ ràng, không phát sinh. Gia đình mình rất hài lòng với phòng Deluxe.', who: 'Quốc Bảo, Đà Nẵng' },
  { text: 'Hủy đơn dễ dàng khi đổi lịch, đây là điều mình thích nhất.', who: 'Thu Hà, TP.HCM' },
]

// Lời khen tự chuyển mỗi 4 giây (chỉ chạy khi tab này đang mở)
function Voices() {
  const [q, setQ] = useState(0)
  useEffect(() => {
    const id = setInterval(() => setQ((i) => (i + 1) % QUOTES.length), 4000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="quote-box">
      <p key={q} className="quote">“{QUOTES[q].text}”</p>
      <span className="quote-who">— {QUOTES[q].who}</span>
      <div className="dots">
        {QUOTES.map((_, i) => (
          <button key={i} className={i === q ? 'on' : ''} onClick={() => setQ(i)} aria-label={`Lời khen ${i + 1}`} />
        ))}
      </div>
    </div>
  )
}

type Tab = 'story' | 'values' | 'voices'
const TABS: { key: Tab; label: string }[] = [
  { key: 'story', label: 'Câu chuyện' },
  { key: 'values', label: 'Giá trị' },
  { key: 'voices', label: 'Khách nói gì' },
]

export default function AboutPage() {
  const [tab, setTab] = useState<Tab>('story')

  return (
    <>
      <PageHead
        title="Về Sứ Hotel"
        sub="Lấy cảm hứng từ sự tinh tế của gốm sứ Việt: giản dị, bền bỉ và ấm áp."
        crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Về Sứ Hotel' }]}
      />

      <div className="stats flat">
        {STATS.map((s) => (
          <div className="stat-item" key={s.label}>
            <b><Counter to={s.to} decimals={s.decimals} suffix={s.suffix} /></b>
            <span>{s.label}</span>
          </div>
        ))}
      </div>

      <div className="utabs" style={{ marginTop: 24 }} role="tablist">
        {TABS.map((x) => (
          <button key={x.key} role="tab" className={`utab ${tab === x.key ? 'on' : ''}`} onClick={() => setTab(x.key)}>
            {x.label}
          </button>
        ))}
      </div>

      <div key={tab} className="tab-anim">
      {tab === 'story' && (
        <div className="panel">
          <p style={{ marginTop: 0 }}>
            Sứ Hotel bắt đầu từ một ý tưởng đơn giản: một chỗ nghỉ chân sạch sẽ, ấm áp và minh bạch về giá.
            Chúng tôi lấy cảm hứng từ gốm sứ Việt, nơi vẻ đẹp đến từ sự tinh tế và bền bỉ.
          </p>
          <p style={{ marginBottom: 0 }}>
            Hiện Sứ Hotel vận hành ba chi nhánh tại Hà Nội, Đà Nẵng và TP.HCM với cùng một tiêu chuẩn phục vụ.
            Khách có thể tìm phòng, đặt phòng và quản lý đơn của mình hoàn toàn trực tuyến.
          </p>
        </div>
      )}

      {tab === 'values' && (
        <div className="features">
          {VALUES.map((v) => (
            <div className="feature" key={v.title}>
              <div className="f-icon"><Icon name={v.icon} size={26} /></div>
              <h4>{v.title}</h4>
              <p>{v.text}</p>
            </div>
          ))}
        </div>
      )}

      {tab === 'voices' && <Voices />}
      </div>
      <div className="cta">
        <h3>Sẵn sàng cho chuyến đi tiếp theo?</h3>
        <p>Chọn chi nhánh, chọn ngày và xác nhận phòng chỉ trong vài phút.</p>
        <Link to="/search" className="btn primary">Tìm phòng trống</Link>
      </div>
    </>
  )
}