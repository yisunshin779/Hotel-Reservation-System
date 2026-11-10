import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { BranchApi, SearchApi } from '../api/hotel'
import HeroFx from '../components/HeroFx'
import { nightsBetween, today, vnd } from '../lib'
import type { SearchParams } from '../types'

const LOOK: Record<string, { emoji: string; cls: string }> = {
  Standard: { emoji: '🛏️', cls: 'v1' },
  Deluxe: { emoji: '🌆', cls: 'v2' },
  Suite: { emoji: '👑', cls: 'v3' },
}

const FEATURES = [
  { icon: '⚡', title: 'Phòng trống thời gian thực', text: 'Xem ngay phòng còn trống và giá chính xác theo ngày bạn chọn.' },
  { icon: '🛡️', title: 'Hủy miễn phí', text: 'Được hủy hoặc đổi đơn miễn phí trước 48 giờ nhận phòng.' },
  { icon: '🎁', title: 'Ưu đãi thành viên', text: 'Nhập mã khuyến mãi khi đặt phòng để được giảm giá ngay.' },
]

export default function SearchPage() {
  const { t } = useTranslation()
  const navigate = useNavigate()

  const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
  const [p, setP] = useState<SearchParams>({
    branchId: '', checkIn: today(), checkOut: tomorrow, adults: 2, children: 0, rooms: 1,
  })
  // Đã bấm "Tìm phòng trống" ít nhất một lần chưa. Sau đó kết quả tự cập nhật theo form
  const [searched, setSearched] = useState(false)

  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })

  const nights = nightsBetween(p.checkIn, p.checkOut)
  const dateError =
    p.checkIn < today() ? 'Ngày nhận phòng không được ở quá khứ.'
    : nights < 1 ? 'Ngày trả phòng phải sau ngày nhận phòng.'
    : ''
  const countError =
    p.adults < 1 || p.rooms < 1 || p.rooms > 5 || p.children < 0
      ? 'Số người lớn từ 1, số phòng từ 1 đến 5.'
      : ''
  const ready = searched && !!p.branchId && !dateError && !countError

  const result = useQuery({
    queryKey: ['availability', p],
    queryFn: () => SearchApi.search(p),
    enabled: ready,
    placeholderData: keepPreviousData, // giữ kết quả cũ trong lúc tính lại
  })

  function update<K extends keyof SearchParams>(key: K, value: SearchParams[K]) {
    setP({ ...p, [key]: value })
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!dateError && !countError && p.branchId) setSearched(true)
  }

  const formError = dateError || countError

  return (
    <div className="home">
      <section className="home-hero hero-photo hero-search">
        <HeroFx />
        <div className="hero-text">
          <span className="badge-top">🏨 Sứ Hotel</span>
          <h1>{t('Tìm nơi nghỉ ngơi')}<br />{t('hoàn hảo cho chuyến đi của bạn')}</h1>
          <p>{t('Đặt phòng nhanh chóng, giá rõ ràng, xác nhận ngay.')}</p>
        </div>
        <svg className="wave" viewBox="0 0 1200 80" preserveAspectRatio="none" aria-hidden="true">
          <path d="M0,40 C200,90 400,0 600,40 C800,80 1000,10 1200,40 L1200,80 L0,80 Z" fill="#fff8ef" />
        </svg>
      </section>

      <div className="home-body">
        <form className="search-card" onSubmit={handleSubmit}>
          <label>
            📍 {t('Chi nhánh')}
            <select required value={p.branchId} onChange={(e) => update('branchId', e.target.value)}>
              <option value="">{t('-- Chọn chi nhánh --')}</option>
              {branches.data?.map((b) => (
                <option key={b.branchId} value={b.branchId}>{t(b.name)}</option>
              ))}
            </select>
          </label>
          <label>
            📅 {t('Nhận phòng')}
            <input type="date" min={today()} value={p.checkIn} onChange={(e) => update('checkIn', e.target.value)} />
          </label>
          <label>
            📅 {t('Trả phòng')}
            <input type="date" min={p.checkIn} value={p.checkOut} onChange={(e) => update('checkOut', e.target.value)} />
          </label>
          <label>
            👤 {t('Người lớn')}
            <input type="number" min={1} value={p.adults} onChange={(e) => update('adults', +e.target.value)} />
          </label>
          <label>
            🧒 {t('Trẻ em')}
            <input type="number" min={0} value={p.children} onChange={(e) => update('children', +e.target.value)} />
          </label>
          <label>
            🚪 {t('Số phòng')}
            <input type="number" min={1} max={5} value={p.rooms} onChange={(e) => update('rooms', +e.target.value)} />
          </label>
          <button className="btn primary shine search-btn" disabled={!!formError || !p.branchId}>
            🔎 {t('Tìm phòng trống')}
          </button>
        </form>
        {formError && <p className="err center">{t(formError)}</p>}
        {ready && <p className="mut center live-hint">{t('Kết quả tự cập nhật khi bạn thay đổi thông tin ở trên.')}</p>}

        {ready && result.isLoading && (
          <div className="results">
            {[0, 1].map((i) => <div key={i} className="room-card skeleton" />)}
          </div>
        )}
        {ready && result.error && <p className="err center">{t((result.error as Error).message)}</p>}
        {ready && result.data?.length === 0 && (
          <div className="empty">😔 {t('Không còn phòng phù hợp. Hãy thử đổi ngày hoặc số phòng.')}</div>
        )}

        {ready && !!result.data?.length && (
          <>
            <h2 className="section-title">
              {t('{{n}} hạng phòng · {{nights}} đêm', { n: result.data.length, nights })}
            </h2>
            <div className={`results ${result.isFetching ? 'updating' : ''}`}>
              {result.data.map((a, i) => {
                const look = LOOK[a.roomType.name] ?? { emoji: '🛏️', cls: 'v1' }
                return (
                  <div className="room-card" key={a.roomType.roomTypeId} style={{ animationDelay: `${i * 0.12}s` }}>
                    <div className={`rv ${look.cls}`}>
                      <span className="rv-emoji">{look.emoji}</span>
                      <span className={`rv-badge ${a.available <= 3 ? 'low' : ''}`}>
                        {a.available <= 3
                          ? t('Chỉ còn {{n}} phòng', { n: a.available })
                          : t('Còn {{n}} phòng', { n: a.available })}
                      </span>
                    </div>
                    <div className="rc-info">
                      <h3>{a.roomType.name}</h3>
                      <p className="rc-desc">{t(a.roomType.description)}</p>
                      <div>
                        {a.roomType.amenities.map((x) => <span className="tag" key={x}>{t(x)}</span>)}
                        <span className="tag">👥 {t('Tối đa {{n}} người', { n: a.roomType.capacity })}</span>
                      </div>
                    </div>
                    <div className="rc-price">
                      <small>{t('Từ {{price}}/đêm', { price: vnd(a.roomType.basePrice) })}</small>
                      <div className="price">{vnd(a.total)}</div>
                      <small>{t('{{nights}} đêm × {{rooms}} phòng', { nights, rooms: p.rooms })}</small>
                      <Link className="btn btn-fit" to={`/rooms/${a.roomType.roomTypeId}`} state={{ search: p }}>
                        {t('Xem chi tiết')}
                      </Link>
                      <button
                        className="btn primary shine"
                        disabled={result.isFetching}
                        onClick={() => navigate('/book', { state: { search: p, item: a } })}
                      >
                        {t('Đặt phòng')}
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>
          </>
        )}

        <h2 className="section-title">{t('Vì sao chọn Sứ Hotel?')}</h2>
        <div className="features">
          {FEATURES.map((f, i) => (
            <div className="feature" key={f.title} style={{ animationDelay: `${i * 0.15}s` }}>
              <div className="f-icon">{f.icon}</div>
              <h4>{t(f.title)}</h4>
              <p>{t(f.text)}</p>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}