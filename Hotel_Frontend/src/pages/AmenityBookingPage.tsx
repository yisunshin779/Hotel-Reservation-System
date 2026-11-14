import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { AmenityApi } from '../api/amenities'
import { useAuth } from '../store/auth'
import PageHead from '../components/PageHead'

const localDate = () => {
  const day = new Date()
  return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`
}

export default function AmenityBookingPage() {
  const { id = '' } = useParams()
  const item = AmenityApi.get(id)
  const user = useAuth((state) => state.user)!
  const navigate = useNavigate()
  const [date, setDate] = useState(localDate)
  const [time, setTime] = useState('10:00')
  const [guests, setGuests] = useState(1)
  const [note, setNote] = useState('')
  const [error, setError] = useState('')
  if (!item) return <div className="card">Không tìm thấy dịch vụ. <Link to="/amenities">Quay lại danh sách</Link></div>

  function submit(event: FormEvent) {
    event.preventDefault()
    setError('')
    try {
      AmenityApi.create({ userId: user.id, amenityId: id, date, time, guests, note: note.trim() })
      navigate('/my-amenities', { replace: true, state: { created: true } })
    } catch (caught) { setError((caught as Error).message) }
  }

  return <div className="amenities-page">
    <PageHead title={`Đặt lịch ${item.name}`} sub="Chọn thời gian mong muốn để gửi yêu cầu."
      crumbs={[{ to: '/amenities', label: 'Dịch vụ tiện ích' }, { to: `/amenities/${id}`, label: item.name }, { label: 'Đặt lịch' }]} />
    <div className="amenity-book-layout"><form className="panel amenity-book-form" onSubmit={submit}>
      <label>Ngày sử dụng<input type="date" required min={localDate()} value={date} onChange={(event) => setDate(event.target.value)} /></label>
      <label>Giờ dự kiến<input type="time" required value={time} onChange={(event) => setTime(event.target.value)} /></label>
      <label>Số người<input type="number" required min={1} max={20} value={guests} onChange={(event) => setGuests(Number(event.target.value))} /></label>
      <label>Ghi chú cho chi nhánh<textarea rows={4} maxLength={300} value={note} onChange={(event) => setNote(event.target.value)} placeholder="Ví dụ: cần bàn cho trẻ em" /></label>
      {error && <p className="err" role="alert">{error}</p>}
      <button className="btn primary">Gửi yêu cầu đặt lịch</button>
    </form><aside className="amenity-book-aside"><h2>{item.name}</h2><p>{item.description}</p><p>Giờ phục vụ: {item.hours}</p>
      <p className="amenity-note">Bản demo lưu yêu cầu trên trình duyệt này. Chưa kiểm tra chỗ trống và chưa gửi đến lễ tân.</p>
      <Link to={`/amenities/${id}`}>← Xem lại dịch vụ</Link>
    </aside></div>
  </div>
}
