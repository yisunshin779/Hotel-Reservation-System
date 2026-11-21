import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { BranchApi, SearchApi } from '../api/hotel'
import PageHead from '../components/PageHead'
import { nightsBetween, today, vnd } from '../lib'
import type { SearchParams } from '../types'

const LOOK: Record<string, { emoji: string; cls: string }> = {
  Standard: { emoji: '🛏️', cls: 'v1' },
  Deluxe: { emoji: '🌆', cls: 'v2' },
  Suite: { emoji: '👑', cls: 'v3' },
}

export default function SearchPage() {
  const navigate = useNavigate()
  // Từ trang Chi nhánh bấm sang thì chọn sẵn chi nhánh và tìm luôn
  const presetBranch = (useLocation().state as { branchId?: string } | null)?.branchId ?? ''

  const [p, setP] = useState<SearchParams>(() => ({
    branchId: presetBranch,
    checkIn: today(),
    checkOut: new Date(Date.now() + 86400000).toISOString().slice(0, 10),
    adults: 2, children: 0, rooms: 1,
  }))
  // Đã bấm tìm ít nhất một lần chưa. Sau đó kết quả tự cập nhật theo form
  const [searched, setSearched] = useState(!!presetBranch)

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
  const formError = dateError || countError
  const ready = searched && !!p.branchId && !formError

  const result = useQuery({
    queryKey: ['availability', p],
    queryFn: () => SearchApi.search(p),
    enabled: ready,
    placeholderData: keepPreviousData,
  })

  function update<K extends keyof SearchParams>(key: K, value: SearchParams[K]) {
    setP({ ...p, [key]: value })
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!formError && p.branchId) setSearched(true)
  }

  return (
    <>
      <PageHead
        title="Tìm phòng"
        sub="Chọn chi nhánh và ngày ở. Kết quả tự cập nhật khi bạn thay đổi thông tin."
        crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Tìm phòng' }]}
      />

      <form className="search-card flat motion-search" onSubmit={handleSubmit}>
        <label>
          Chi nhánh
          <select required value={p.branchId} onChange={(e) => update('branchId', e.target.value)}>
            <option value="">-- Chọn chi nhánh --</option>
            {branches.data?.map((b) => <option key={b.branchId} value={b.branchId}>{b.name}</option>)}
          </select>
        </label>
        <label>
          Nhận phòng
          <input type="date" min={today()} value={p.checkIn} onChange={(e) => update('checkIn', e.target.value)} />
        </label>
        <label>
          Trả phòng
          <input type="date" min={p.checkIn} value={p.checkOut} onChange={(e) => update('checkOut', e.target.value)} />
        </label>
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
        <button className="btn primary search-btn" disabled={!!formError || !p.branchId}>Tìm phòng trống</button>
      </form>
      {formError && <p className="err">{formError}</p>}

      {ready && result.isLoading && (
        <div className="results" style={{ marginTop: 18 }}>
          {[0, 1].map((i) => <div key={i} className="room-card skeleton" />)}
        </div>
      )}
      {ready && result.error && <p className="err">{(result.error as Error).message}</p>}
      {ready && result.data?.length === 0 && (
        <div className="empty">Không còn phòng phù hợp. Hãy thử đổi ngày hoặc số phòng.</div>
      )}

      {ready && !!result.data?.length && (
        <>
          <h2 className="section-title">{result.data.length} hạng phòng · {nights} đêm</h2>
          <div className={`results motion-stagger ${result.isFetching ? 'updating' : ''}`}>
            {result.data.map((a) => {
              const look = LOOK[a.roomType.name] ?? { emoji: '🛏️', cls: 'v1' }
              return (
                <div className="room-card" key={a.roomType.roomTypeId}>
                  <div className={`rv ${look.cls}`}>
                    <span className="rv-emoji">{look.emoji}</span>
                    <span className={`rv-badge ${a.available <= 3 ? 'low' : ''}`}>
                      {a.available <= 3 ? `Chỉ còn ${a.available} phòng` : `Còn ${a.available} phòng`}
                    </span>
                  </div>
                  <div className="rc-info">
                    <h3>{a.roomType.name}</h3>
                    <p className="rc-desc">{a.roomType.description}</p>
                    <div>
                      {a.roomType.amenities.map((x) => <span className="tag" key={x}>{x}</span>)}
                      <span className="tag">Tối đa {a.roomType.capacity} người</span>
                    </div>
                  </div>
                  <div className="rc-price">
                    <small>Từ {vnd(a.roomType.basePrice)}/đêm</small>
                    <div className="price">{vnd(a.total)}</div>
                    <small>{nights} đêm × {p.rooms} phòng</small>
                    <Link className="btn btn-fit" to={`/rooms/${a.roomType.roomTypeId}`} state={{ search: p }}>
                      Xem chi tiết
                    </Link>
                    <button
                      className="btn primary"
                      disabled={result.isFetching}
                      onClick={() => navigate('/book', { state: { search: p, item: a } })}
                    >
                      Đặt phòng
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        </>
      )}
    </>
  )
}
