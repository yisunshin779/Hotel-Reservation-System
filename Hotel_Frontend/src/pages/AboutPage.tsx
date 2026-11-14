import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import HeroFx from '../components/HeroFx'

// Số chạy từ 0 đến giá trị đích khi cuộn tới
function Counter({ to, decimals = 0, suffix = '' }: { to: number; decimals?: number; suffix?: string }) {
  const { i18n } = useTranslation()
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

  const shown = Number(val.toFixed(decimals)).toLocaleString(i18n.language === 'en' ? 'en-US' : 'vi-VN')
  return <span ref={ref}>{shown}{suffix}</span>
}

const STATS = [
  { to: 3, label: 'Chi nhánh', suffix: '' },
  { to: 30, label: 'Phòng nghỉ', suffix: '' },
  { to: 10000, label: 'Khách đã đặt phòng', suffix: '+' },
  { to: 4.8, label: 'Điểm đánh giá', suffix: '/5', decimals: 1 },
]

const BRANCHES = [
  { emoji: '🏯', name: 'Hà Nội – Hoàn Kiếm', text: 'Giữa phố cổ, bước chân ra là hồ Gươm và những quán cà phê trứng nổi tiếng.' },
  { emoji: '🏖️', name: 'Đà Nẵng – Mỹ Khê', text: 'Cách biển vài phút đi bộ, view bình minh trên bãi biển đẹp nhất hành tinh.' },
  { emoji: '🌃', name: 'TP.HCM – Quận 1', text: 'Trung tâm sôi động, thuận tiện đi lại, ăn uống và mua sắm cả ngày lẫn đêm.' },
]

const QUOTES = [
  { text: 'Phòng sạch sẽ, nhân viên thân thiện, đặt phòng chỉ mất vài phút.', who: 'Minh Anh, Hà Nội' },
  { text: 'Giá rõ ràng, không phát sinh. Gia đình mình rất hài lòng với phòng Deluxe.', who: 'Quốc Bảo, Đà Nẵng' },
  { text: 'Hủy đơn dễ dàng khi đổi lịch, đây là điều mình thích nhất.', who: 'Thu Hà, TP.HCM' },
]

export default function AboutPage() {
  const { t } = useTranslation()
  const [q, setQ] = useState(0)

  // Lời khen tự chuyển mỗi 4 giây
  useEffect(() => {
    const id = setInterval(() => setQ((i) => (i + 1) % QUOTES.length), 4000)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="home">
      <section className="home-hero hero-photo hero-about">
        <HeroFx />
        <div className="hero-text">
          <span className="badge-top">🏨 {t('Câu chuyện của chúng tôi')}</span>
          <h1>
            {t('Sứ Hotel – nơi mỗi chuyến đi')}<br />{t('đều có một chỗ nghỉ chân đáng nhớ')}
          </h1>
          <p>{t('Lấy cảm hứng từ sự tinh tế của gốm sứ Việt: giản dị, bền bỉ và ấm áp.')}</p>
        </div>
        <svg className="wave" viewBox="0 0 1200 80" preserveAspectRatio="none" aria-hidden="true">
          <path d="M0,40 C200,90 400,0 600,40 C800,80 1000,10 1200,40 L1200,80 L0,80 Z" fill="#fff8ef" />
        </svg>
      </section>

      <div className="home-body">
        <div className="stats">
          {STATS.map((s) => (
            <div className="stat-item" key={s.label}>
              <b><Counter to={s.to} decimals={s.decimals} suffix={s.suffix} /></b>
              <span>{t(s.label)}</span>
            </div>
          ))}
        </div>

        <h2 className="section-title">{t('Ba điểm đến của Sứ Hotel')}</h2>
        <div className="features">
          {BRANCHES.map((b, i) => (
            <div className="feature" key={b.name} style={{ animationDelay: `${i * 0.15}s` }}>
              <div className="f-icon">{b.emoji}</div>
              <h4>{t(b.name)}</h4>
              <p>{t(b.text)}</p>
            </div>
          ))}
        </div>

        <h2 className="section-title">{t('Khách nói gì về chúng tôi')}</h2>
        <div className="quote-box">
          <p key={q} className="quote">“{t(QUOTES[q].text)}”</p>
          <span className="quote-who">— {t(QUOTES[q].who)}</span>
          <div className="dots">
            {QUOTES.map((_, i) => (
              <button key={i} className={i === q ? 'on' : ''} onClick={() => setQ(i)} aria-label={t('Lời khen {{n}}', { n: i + 1 })} />
            ))}
          </div>
        </div>

        <div className="cta">
          <h3>{t('Sẵn sàng cho chuyến đi tiếp theo?')}</h3>
          <p>{t('Chọn ngày, chọn chi nhánh, xác nhận phòng chỉ trong vài phút.')}</p>
          <Link to="/search" className="btn primary shine">{t('Đặt phòng ngay')}</Link>
        </div>
      </div>
    </div>
  )
}