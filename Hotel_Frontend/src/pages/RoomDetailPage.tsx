import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { BranchApi, RoomTypeApi, SearchApi } from '../api/hotel'
import { useAuth } from '../store/auth'
import { nightsBetween, today, vnd } from '../lib'
import type { SearchParams } from '../types'

// Thông tin mô tả thêm cho từng hạng phòng (nội dung mẫu, bạn sửa lại cho khớp khách sạn của nhóm)
const EXTRA: Record<string, {
  cls: string; size: string; bed: string; view: string
  shots: { emoji: string; label: string }[]
  features: string[]
}> = {
  Standard: {
    cls: 'v1', size: '25 m²', bed: '1 giường đôi', view: 'Nội khu yên tĩnh',
    shots: [
      { emoji: '🛏️', label: 'Phòng ngủ' }, { emoji: '🚿', label: 'Phòng tắm' },
      { emoji: '💻', label: 'Góc làm việc' }, { emoji: '🌿', label: 'View nội khu' },
    ],
    features: ['Điều hòa nhiệt độ', 'Wifi tốc độ cao', 'TV màn hình phẳng', 'Két an toàn', 'Ấm đun nước, trà, cà phê', 'Đồ vệ sinh cá nhân miễn phí'],
  },
  Deluxe: {
    cls: 'v2', size: '32 m²', bed: '1 giường lớn hoặc 2 giường đơn', view: 'Toàn cảnh thành phố',
    shots: [
      { emoji: '🌆', label: 'View thành phố' }, { emoji: '🛏️', label: 'Phòng ngủ' },
      { emoji: '🛁', label: 'Bồn tắm' }, { emoji: '☕', label: 'Góc thư giãn' },
    ],
    features: ['Điều hòa nhiệt độ', 'Wifi tốc độ cao', 'TV 43 inch', 'Bồn tắm nằm', 'Minibar', 'Bàn làm việc rộng', 'Máy pha cà phê'],
  },
  Suite: {
    cls: 'v3', size: '60 m²', bed: '1 giường King', view: 'Toàn cảnh, tầng cao',
    shots: [
      { emoji: '👑', label: 'Phòng Suite' }, { emoji: '🛋️', label: 'Phòng khách riêng' },
      { emoji: '🛁', label: 'Phòng tắm cao cấp' }, { emoji: '🥂', label: 'Lounge' },
    ],
    features: ['Phòng khách riêng', 'Wifi tốc độ cao', 'TV 55 inch', 'Bồn tắm và vòi sen riêng', 'Minibar cao cấp', 'Quyền dùng Lounge', 'Dịch vụ quản gia ưu tiên'],
  },
}

export default function RoomDetailPage() {
  const { t } = useTranslation()
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const user = useAuth((s) => s.user)
    // Lựa chọn từ trang tìm kiếm; nếu khách vừa đi đăng nhập về thì lấy bản đã lưu tạm
  const saved = sessionStorage.getItem('hrs-detail-search')
  const preset =
    (useLocation().state as { search?: SearchParams } | null)?.search ??
    (saved ? (JSON.parse(saved) as SearchParams) : undefined)

  const room = useQuery({ queryKey: ['room-type', id], queryFn: () => RoomTypeApi.get(id) })
  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })

  const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
  const [p, setP] = useState<SearchParams>(
    preset ?? { branchId: '', checkIn: today(), checkOut: tomorrow, adults: 2, children: 0, rooms: 1 }
  )
  const [shot, setShot] = useState(0)

  // Kiểm tra còn phòng với lựa chọn của khách rồi chuyển sang trang đặt phòng
  const check = useMutation({
    mutationFn: async (s: SearchParams) => {
      const list = await SearchApi.search(s)
      const item = list.find((a) => a.roomType.roomTypeId === id)
      if (!item) throw new Error('Hạng phòng này không còn đủ chỗ cho lựa chọn của bạn. Hãy thử đổi ngày hoặc số phòng.')
      return { s, item }
    },
    onSuccess: ({ s, item }) => {
      sessionStorage.removeItem('hrs-detail-search')
      navigate('/book', { state: { search: s, item } })
    },
  })

  const nights = nightsBetween(p.checkIn, p.checkOut)
  const dateError =
    p.checkIn < today() ? 'Ngày nhận phòng không được ở quá khứ.'
    : nights < 1 ? 'Ngày trả phòng phải sau ngày nhận phòng.'
    : ''

   const countError =
    p.adults < 1 || p.rooms < 1 || p.rooms > 5 || p.children < 0
      ? 'Số người lớn từ 1, số phòng từ 1 đến 5.'
      : ''
  const ready = !!p.branchId && !dateError && !countError

  // Báo giá trực tiếp: tự tính lại mỗi khi khách đổi ngày, số người hoặc số phòng
  const quote = useQuery({
    queryKey: ['detail-quote', p],
    queryFn: () => SearchApi.search(p),
    enabled: ready,
    placeholderData: keepPreviousData,
  })
  const quoted = quote.data?.find((a) => a.roomType.roomTypeId === id)
  const soldOut = ready && !quote.isLoading && !quote.isFetching && !quoted

  function update<K extends keyof SearchParams>(key: K, value: SearchParams[K]) {
    setP({ ...p, [key]: value })
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (dateError || !p.branchId) return
    // Chưa đăng nhập thì đưa đi đăng nhập, xong quay lại đúng trang này
    if (!user) {
      sessionStorage.setItem('hrs-detail-search', JSON.stringify(p))
      navigate('/login', { state: { from: `/rooms/${id}` } })
      return
    }
    check.mutate(p)
  }

  if (room.isLoading) return <div className="home"><div className="home-body detail-body">{t('Đang tải...')}</div></div>
  if (room.error || !room.data) {
    return (
      <div className="home">
        <div className="home-body detail-body">
          <p className="err">{t((room.error as Error)?.message ?? 'Không tìm thấy hạng phòng.')}</p>
          <Link to="/search">{t('← Quay lại tìm phòng')}</Link>
        </div>
      </div>
    )
  }

  const r = room.data
  const ex = EXTRA[r.name] ?? EXTRA.Standard

  return (
    <div className="home">
      <div className="home-body detail-body">
        <div className="crumbs">
          <Link to="/">{t('Trang chủ')}</Link> › <Link to="/search">{t('Tìm phòng')}</Link> › {r.name}
        </div>

        <div className="detail-grid">
          {/* Cột trái: thông tin */}
          <div>
            <div className={`gal-main rv ${ex.cls}`}>
              <span className="gal-emoji" key={shot}>{ex.shots[shot].emoji}</span>
              <span className="gal-cap">{t(ex.shots[shot].label)}</span>
            </div>
            <div className="gal-thumbs">
              {ex.shots.map((s, i) => (
                <button key={s.label} type="button" className={i === shot ? 'on' : ''} onClick={() => setShot(i)}>
                  <span>{s.emoji}</span>
                  <small>{t(s.label)}</small>
                </button>
              ))}
            </div>

            <h1 className="detail-title">{r.name}</h1>
            <p className="detail-desc">{t(r.description)}</p>

            <div className="specs">
              <div><span>📐</span><b>{ex.size}</b><small>{t('Diện tích')}</small></div>
              <div><span>🛏️</span><b>{t(ex.bed)}</b><small>{t('Giường')}</small></div>
              <div><span>👥</span><b>{t('Tối đa {{n}} người', { n: r.capacity })}</b><small>{t('Sức chứa')}</small></div>
              <div><span>🪟</span><b>{t(ex.view)}</b><small>{t('Tầm nhìn')}</small></div>
            </div>

            <h2 className="section-title">{t('Tiện nghi trong phòng')}</h2>
            <div className="panel amen">
              {ex.features.map((f) => <div key={f}>✓ {t(f)}</div>)}
            </div>

            <h2 className="section-title">{t('Bảng giá theo mùa (mỗi đêm)')}</h2>
            <div className="panel wrap">
              <table>
                <thead>
                  <tr><th>{t('Ngày thường')}</th><th>{t('Cuối tuần (T6, T7)')}</th><th>{t('Lễ, Tết')}</th></tr>
                </thead>
                <tbody>
                  <tr>
                    <td><b>{vnd(r.basePrice)}</b></td>
                    <td><b>{vnd(Math.round(r.basePrice * 1.15))}</b></td>
                    <td><b>{vnd(Math.round(r.basePrice * 1.4))}</b></td>
                  </tr>
                </tbody>
              </table>
            </div>

            <h2 className="section-title">{t('Chính sách')}</h2>
            <div className="panel policy">
              <div>🕑 {t('Nhận phòng từ 14:00, trả phòng trước 12:00')}</div>
              <div>🛡️ {t('Hủy miễn phí trước 48 giờ nhận phòng')}</div>
              <div>🚭 {t('Phòng không hút thuốc')}</div>
              <div>👶 {t('Có thể kê thêm giường phụ theo yêu cầu (có phụ phí)')}</div>
            </div>
          </div>

          {/* Cột phải: đặt phòng */}
          <form className="panel book-box" onSubmit={handleSubmit}>
            <div className="price">{vnd(r.basePrice)} <small>{t('/ đêm')}</small></div>
            <small className="note">{t('Giá từ, tính theo ngày thường')}</small>

                        {preset?.branchId ? (
              <div className="branch-locked">
                📍 {t('Chi nhánh')}: <b>{t(branches.data?.find((b) => b.branchId === p.branchId)?.name ?? '')}</b>
              </div>
            ) : (
              <label>
                📍 {t('Chi nhánh')}
                <select required value={p.branchId} onChange={(e) => update('branchId', e.target.value)}>
                  <option value="">{t('-- Chọn chi nhánh --')}</option>
                  {branches.data?.map((b) => <option key={b.branchId} value={b.branchId}>{t(b.name)}</option>)}
                </select>
              </label>
            )}
            <div className="two">
              <label>
                {t('Nhận phòng')}
                <input type="date" min={today()} value={p.checkIn} onChange={(e) => update('checkIn', e.target.value)} />
              </label>
              <label>
                {t('Trả phòng')}
                <input type="date" min={p.checkIn} value={p.checkOut} onChange={(e) => update('checkOut', e.target.value)} />
              </label>
            </div>
            <div className="three">
              <label>
                {t('Người lớn')}
                <input type="number" min={1} value={p.adults} onChange={(e) => update('adults', +e.target.value)} />
              </label>
              <label>
                {t('Trẻ em')}
                <input type="number" min={0} value={p.children} onChange={(e) => update('children', +e.target.value)} />
              </label>
              <label>
                {t('Số phòng')}
                <input type="number" min={1} max={5} value={p.rooms} onChange={(e) => update('rooms', +e.target.value)} />
              </label>
            </div>

                        {(dateError || countError) && <p className="err">{t(dateError || countError)}</p>}

            {ready && (
              <div className={`q-total ${quote.isFetching ? 'updating' : ''}`}>
                {quoted && (
                  <>
                    <div className="q-row">
                      <span>{t('{{nights}} đêm × {{rooms}} phòng', { nights, rooms: p.rooms })}</span>
                      <b className="price">{vnd(quoted.total)}</b>
                    </div>
                    <small className={quoted.available <= 3 ? 'err' : 'note'}>
                      {quoted.available <= 3
                        ? t('Chỉ còn {{n}} phòng', { n: quoted.available })
                        : t('Còn {{n}} phòng', { n: quoted.available })}
                    </small>
                  </>
                )}
                {soldOut && (
                  <p className="err" style={{ margin: 0 }}>
                    {t('Hạng phòng này không còn đủ chỗ cho lựa chọn của bạn. Hãy thử đổi ngày hoặc số phòng.')}
                  </p>
                )}
              </div>
            )}
            {check.error && <p className="err">{t((check.error as Error).message)}</p>}

            <button
              className="btn primary shine"
              disabled={check.isPending || !!dateError || !!countError || !p.branchId || soldOut || quote.isFetching}
            >
              {check.isPending ? t('Đang kiểm tra...') : user ? t('Đặt phòng') : t('Đăng nhập để đặt phòng')}
            </button>
            <small className="note">{t('Chưa bị tính phí. Bạn xem lại tổng tiền ở bước sau.')}</small>
          </form>
        </div>
      </div>
    </div>
  )
}