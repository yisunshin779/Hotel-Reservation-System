import { useEffect, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ReviewApi } from '../api/hotel'
import Stars from './Stars'

const LABELS = ['', 'Rất tệ', 'Chưa tốt', 'Bình thường', 'Tốt', 'Tuyệt vời']

interface Props {
  reservationId: string
  code: string
  roomTypeName: string
  onClose: () => void
}

export default function ReviewDialog({ reservationId, code, roomTypeName, onClose }: Props) {
  const queryClient = useQueryClient()
  const [rating, setRating] = useState(0)
  const [comment, setComment] = useState('')

  // Bấm Esc để đóng
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const send = useMutation({
    mutationFn: () => ReviewApi.create(reservationId, rating, comment),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['my-reservations'] })
      queryClient.invalidateQueries({ queryKey: ['reviews'] })
      queryClient.invalidateQueries({ queryKey: ['room-reviews'] })
      onClose()
    },
  })

  return (
    <div className="modal-bg" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="stay-dialog" style={{ maxWidth: 460 }}>
        <div className="sd-head">
          <div>
            <h2>Đánh giá kỳ nghỉ</h2>
            <span className="mut">Đơn {code} · phòng {roomTypeName}</span>
          </div>
          <button className="btn" onClick={onClose}>✕</button>
        </div>

        <div className="rate-pick">
          <Stars value={rating} onChange={setRating} size={38} />
          <div className="rate-label">{rating ? LABELS[rating] : 'Bấm vào sao để chấm điểm'}</div>
        </div>

        <label>
          Nhận xét của bạn (không bắt buộc)
          <textarea
            rows={4}
            maxLength={500}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            placeholder="Phòng ốc, nhân viên, bữa sáng, vị trí..."
          />
          <small className="mut">{comment.length}/500</small>
        </label>

        {send.error && <p className="err">{(send.error as Error).message}</p>}

        <div className="sd-foot">
          <button className="btn primary shine" disabled={!rating || send.isPending} onClick={() => send.mutate()}>
            {send.isPending ? 'Đang gửi...' : 'Gửi đánh giá'}
          </button>
        </div>
      </div>
    </div>
  )
}