import { useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { BranchApi, SearchApi } from '../api/hotel'
import { nightsBetween, today, vnd } from '../lib'
import type { SearchParams } from '../types'

export default function SearchPage() {
  const navigate = useNavigate()

  // Dữ liệu trong form
  const [p, setP] = useState<SearchParams>({
    branchId: '', checkIn: today(), checkOut: today(), adults: 2, children: 0, rooms: 1,
  })
  // Dữ liệu đã bấm "Tìm" (chỉ đổi khi bấm nút)
  const [submitted, setSubmitted] = useState<SearchParams | null>(null)

  const branches = useQuery({ queryKey: ['branches'], queryFn: BranchApi.list })
  const result = useQuery({
    queryKey: ['availability', submitted],
    queryFn: () => SearchApi.search(submitted!),
    enabled: submitted !== null,
  })

  const nights = nightsBetween(p.checkIn, p.checkOut)
  const dateError =
    p.checkIn < today() ? 'Ngày nhận phòng không được ở quá khứ.'
    : nights < 1 ? 'Ngày trả phòng phải sau ngày nhận phòng.'
    : ''

  function update<K extends keyof SearchParams>(key: K, value: SearchParams[K]) {
    setP({ ...p, [key]: value })
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!dateError && p.branchId) setSubmitted(p)
  }

  return (
    <>
      <h1>Tìm phòng</h1>

      <form className="card grid" onSubmit={handleSubmit}>
        <label>
          Chi nhánh
          <select required value={p.branchId} onChange={(e) => update('branchId', e.target.value)}>
            <option value="">-- Chọn --</option>
            {branches.data?.map((b) => (
              <option key={b.branchId} value={b.branchId}>{b.name}</option>
            ))}
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
        <button className="btn primary" disabled={!!dateError || !p.branchId}>
          Tìm phòng trống
        </button>
      </form>

      {dateError && <p className="err">{dateError}</p>}
      {result.isLoading && <p>Đang tìm phòng...</p>}
      {result.error && <p className="err">{(result.error as Error).message}</p>}
      {result.data?.length === 0 && (
        <div className="card">Không còn phòng phù hợp. Hãy thử đổi ngày hoặc số phòng.</div>
      )}

      {result.data?.map((a) => (
        <div className="card room" key={a.roomType.roomTypeId}>
          <div className="ph">🛏️</div>
          <div className="grow">
            <h2>{a.roomType.name}</h2>
            <p>{a.roomType.description}</p>
            <div>
              {a.roomType.amenities.map((x) => <span className="tag" key={x}>{x}</span>)}
            </div>
            <p className={a.available <= 3 ? 'err' : 'mut'}>Còn {a.available} phòng</p>
          </div>
          <div className="right">
            <div className="price">{vnd(a.total)}</div>
            <small className="mut">
              {nightsBetween(submitted!.checkIn, submitted!.checkOut)} đêm × {submitted!.rooms} phòng
            </small>
            <br />
            <button
              className="btn primary"
              onClick={() => navigate('/book', { state: { search: submitted, item: a } })}
            >
              Đặt phòng
            </button>
          </div>
        </div>
      ))}
    </>
  )
}