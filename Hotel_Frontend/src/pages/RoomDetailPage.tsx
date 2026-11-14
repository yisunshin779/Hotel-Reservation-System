import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { BranchApi, RoomTypeApi, SearchApi } from '../api/hotel'
import PageHead from '../components/PageHead'
import RoomReviews from '../components/RoomReviews'
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

type Tab = 'overview' | 'amenities' | 'pricing' | 'reviews'
const TABS: { key: Tab; label: string }[] = [
  { key: 'overview', label: 'Tổng quan' },
  { key: 'amenities', label: 'Tiện nghi' },
  { key: 'pricing', label: 'Giá & chính sách' },
  { key: 'reviews', label: 'Đánh giá' },
]

export default function RoomDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const user = useAuth((s) => s.user)

  const room = useQuery({ queryKey: ['room-type', id], queryFn: () => RoomTypeApi.get(id) })
  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })

  // Lựa chọn từ trang tìm kiếm; nếu khách vừa đi đăng nhập về thì lấy bản đã lưu tạm
  const saved = sessionStorage.getItem('hrs-detail-search')
  const preset =
    (location.state as { search?: SearchParams } | null)?.search ??
    (saved ? (JSON.parse(saved) as SearchParams) : undefined)

  const tomorrow = new Date(Date.now() + 86400000).toISOString().slice(0, 10)
  const [p, setP] = useState<SearchParams>(
    preset ?? { branchId: '', checkIn: today(), checkOut: tomorrow, adults: 2, children: 0, rooms: 1 }
  )
  const [tab, setTab] = useState<Tab>('overview')
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
    if (dateError || countError || !p.branchId) return
    // Chưa đăng nhập thì đưa đi đăng nhập, xong quay lại đúng trang này
    if (!user) {
      sessionStorage.setItem('hrs-detail-search', JSON.stringify(p))
      navigate('/login', { state: { from: `/rooms/${id}` } })
      return
    }
    check.mutate(p)
  }

  if (room.isLoading) return <p>Đang tải...</p>
  if (room.error || !room.data) {
    return (
      <>
        <p className="err">{(room.error as Error)?.message ?? 'Không tìm thấy hạng phòng.'}</p>
        <Link to="/rooms">← Quay lại danh sách hạng phòng</Link>
      </>
    )
  }

  const r = room.data
  const ex = EXTRA[r.name] ?? EXTRA.Standard

  return (
    <>
      <PageHead
        title={r.name}
        sub={r.description}
        crumbs={[{ to: '/', label: 'Trang chủ' }, { to: '/rooms', label: 'Hạng phòng' }, { label: r.name }]}
      />

      <div className="detail-grid">
        <div>
          <div className="utabs" role="tablist">
            {TABS.map((x) => (
              <button key={x.key} role="tab" className={`utab ${tab === x.key ? 'on' : ''}`} onClick={() => setTab(x.key)}>
                {x.label}
              </button>
            ))}
          </div>
          <div key={tab} className="tab-anim">
          {tab === 'overview' && (
            <>
              <div className={`gal-main rv ${ex.cls}`}>
                <span className="gal-emoji" key={shot}>{ex.shots[shot].emoji}</span>
                <span className="gal-cap">{ex.shots[shot].label}</span>
              </div>
              <div className="gal-thumbs">
                {ex.shots.map((s, i) => (
                  <button key={s.label} type="button" className={i === shot ? 'on' : ''} onClick={() => setShot(i)}>
                    <span>{s.emoji}</span>
                    <small>{s.label}</small>
                  </button>
                ))}
              </div>
              <div className="specs" style={{ marginTop: 16 }}>
                <div><small>Diện tích</small><b>{ex.size}</b></div>
                <div><small>Giường</small><b>{ex.bed}</b></div>
                <div><small>Sức chứa</small><b>Tối đa {r.capacity} người</b></div>
                <div><small>Tầm nhìn</small><b>{ex.view}</b></div>
              </div>
            </>
          )}

          {tab === 'amenities' && (
            <div className="panel amen">
              {ex.features.map((f) => <div key={f}>✓ {f}</div>)}
            </div>
          )}

          {tab === 'pricing' && (
            <>
              <h2 className="section-title" style={{ marginTop: 0 }}>Bảng giá theo mùa (mỗi đêm)</h2>
              <div className="panel wrap">
                <table>
                  <thead><tr><th>Ngày thường</th><th>Cuối tuần (T6, T7)</th><th>Lễ, Tết</th></tr></thead>
                  <tbody>
                    <tr>
                      <td><b>{vnd(r.basePrice)}</b></td>
                      <td><b>{vnd(Math.round(r.basePrice * 1.15))}</b></td>
                      <td><b>{vnd(Math.round(r.basePrice * 1.4))}</b></td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <h2 className="section-title">Chính sách</h2>
              <div className="panel policy">
                <div>Nhận phòng từ <b>14:00</b>, trả phòng trước <b>12:00</b></div>
                <div>Hủy miễn phí trước <b>48 giờ</b> nhận phòng</div>
                <div>Phòng không hút thuốc</div>
                <div>Có thể kê thêm giường phụ theo yêu cầu (có phụ phí)</div>
              </div>
            </>
          )}

          {tab === 'reviews' && <RoomReviews roomName={r.name} />}
          </div>
        </div>

        {/* Khung đặt phòng luôn nằm bên phải */}
        <form className="panel book-box" onSubmit={handleSubmit}>
          <div className="price">{vnd(r.basePrice)} <small>/ đêm</small></div>
          <small className="note">Giá từ, tính theo ngày thường</small>

          {preset?.branchId ? (
            <div className="branch-locked">
              Chi nhánh: <b>{branches.data?.find((b) => b.branchId === p.branchId)?.name ?? ''}</b>
            </div>
          ) : (
            <label>
              Chi nhánh
              <select required value={p.branchId} onChange={(e) => update('branchId', e.target.value)}>
                <option value="">-- Chọn chi nhánh --</option>
                {branches.data?.map((b) => <option key={b.branchId} value={b.branchId}>{b.name}</option>)}
              </select>
            </label>
          )}

          <div className="two">
            <label>
              Nhận phòng
              <input type="date" min={today()} value={p.checkIn} onChange={(e) => update('checkIn', e.target.value)} />
            </label>
            <label>
              Trả phòng
              <input type="date" min={p.checkIn} value={p.checkOut} onChange={(e) => update('checkOut', e.target.value)} />
            </label>
          </div>
          <div className="three">
            <label>
              Người lớn
              <input type="number" min={1} value={p.adults} onChange={(e) => update('adults', +e.target.value)} />
            </label>
            <label>
              Trẻ em
              <input type="number" min={0} value={p.children} onChange={(e) => update('children', +e.target.value)} />
            </label>
            <label>
              Số phòng
              <input type="number" min={1} max={5} value={p.rooms} onChange={(e) => update('rooms', +e.target.value)} />
            </label>
          </div>

          {(dateError || countError) && <p className="err">{dateError || countError}</p>}

          {ready && (
            <div className={`q-total ${quote.isFetching ? 'updating' : ''}`}>
              {quoted && (
                <>
                  <div className="q-row">
                    <span>{nights} đêm × {p.rooms} phòng</span>
                    <b className="price">{vnd(quoted.total)}</b>
                  </div>
                  <small className={quoted.available <= 3 ? 'err' : 'note'}>
                    {quoted.available <= 3 ? `Chỉ còn ${quoted.available} phòng` : `Còn ${quoted.available} phòng`}
                  </small>
                </>
              )}
              {soldOut && (
                <p className="err" style={{ margin: 0 }}>
                  Hạng phòng này không còn đủ chỗ cho lựa chọn của bạn. Hãy thử đổi ngày hoặc số phòng.
                </p>
              )}
            </div>
          )}

          {check.error && <p className="err">{(check.error as Error).message}</p>}

          <button
            className="btn primary"
            disabled={check.isPending || !!dateError || !!countError || !p.branchId || soldOut || quote.isFetching}
          >
            {check.isPending ? 'Đang kiểm tra...' : user ? 'Đặt phòng' : 'Đăng nhập để đặt phòng'}
          </button>
          <small className="note">Chưa bị tính phí. Bạn xem lại tổng tiền ở bước sau.</small>
        </form>
      </div>
    </>
  )
}