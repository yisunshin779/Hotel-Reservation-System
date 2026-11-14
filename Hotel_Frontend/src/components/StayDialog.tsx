import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StayApi } from '../api/hotel'
import { STATUS_LABEL, serviceTotal, vnd } from '../lib'

// Cửa sổ chi tiết lưu trú: đổi phòng, ghi dịch vụ, xem và in hóa đơn
export default function StayDialog({ id, onClose }: { id: string; onClose: () => void }) {
  const queryClient = useQueryClient()
  const stay = useQuery({ queryKey: ['stay', id], queryFn: () => StayApi.get(id) })
  const catalog = useQuery({ queryKey: ['services'], queryFn: StayApi.services })
  const r = stay.data
  const inHouse = r?.status === 'CheckedIn'
  const free = useQuery({
    queryKey: ['free-rooms', id],
    queryFn: () => StayApi.freeRooms(id),
    enabled: inHouse,
  })

  const [serviceId, setServiceId] = useState('')
  const [qty, setQty] = useState(1)
  const [target, setTarget] = useState<Record<string, string>>({})

  // Bấm Esc để đóng
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['stay', id] })
    queryClient.invalidateQueries({ queryKey: ['free-rooms', id] })
    queryClient.invalidateQueries({ queryKey: ['staff-reservations'] })
    queryClient.invalidateQueries({ queryKey: ['rooms'] })
  }
  const add = useMutation({
    mutationFn: () => StayApi.addService(id, serviceId, qty),
    onSuccess: () => { refresh(); setQty(1) },
  })
  const remove = useMutation({ mutationFn: (chargeId: string) => StayApi.removeService(id, chargeId), onSuccess: refresh })
  const move = useMutation({
    mutationFn: (v: { from: string; to: string }) => StayApi.changeRoom(id, v.from, v.to),
    onSuccess: (_d, v) => {
      setTarget((t) => ({ ...t, [v.from]: '' }))
      refresh()
    },
  })

  const error = (add.error || remove.error || move.error) as Error | null

  let body = <p>Đang tải...</p>
  if (stay.error) body = <p className="err">{(stay.error as Error).message}</p>

  if (r) {
    const rooms = r.roomNumbers ?? (r.roomNumber ? [r.roomNumber] : [])
    const svcSum = serviceTotal(r.services)
    const total = r.totalAmount + svcSum
    const due = Math.max(0, total - r.paidAmount)

    body = (
      <>
        <div className="sd-head">
          <div>
            <h2>Đơn {r.code}</h2>
            <span className="mut">
              {r.guestName} · {r.roomTypeName}{(r.quantity ?? 1) > 1 ? ` × ${r.quantity}` : ''} ·{' '}
              {r.checkInDate} → {r.checkOutDate} · {STATUS_LABEL[r.status]}
            </span>
          </div>
          <button className="btn no-print" onClick={onClose}>✕ Đóng</button>
        </div>

        {error && <p className="err">{error.message}</p>}

        <section className="sd-sec">
          <h3>Phòng đang ở</h3>
          {rooms.length === 0 && <p className="mut">Chưa gán phòng.</p>}
          {rooms.map((no) => (
            <div className="sd-row" key={no}>
              <b>Phòng {no}</b>
              {inHouse && (
                <span className="no-print sd-move">
                  <select value={target[no] ?? ''} onChange={(e) => setTarget({ ...target, [no]: e.target.value })}>
                    <option value="">Đổi sang phòng...</option>
                    {free.data?.map((x) => <option key={x.roomId} value={x.roomNumber}>{x.roomNumber}</option>)}
                  </select>
                  <button
                    className="btn"
                    disabled={!target[no] || move.isPending}
                    onClick={() => move.mutate({ from: no, to: target[no] })}
                  >
                    Đổi phòng
                  </button>
                </span>
              )}
            </div>
          ))}
          {inHouse && free.data?.length === 0 && (
            <p className="mut no-print">Hiện không còn phòng trống cùng hạng để đổi.</p>
          )}
        </section>

        <section className="sd-sec">
          <h3>Dịch vụ đã sử dụng</h3>
          {(r.services ?? []).length === 0 && <p className="mut">Chưa có dịch vụ nào.</p>}
          {(r.services ?? []).map((s) => (
            <div className="sd-row" key={s.id}>
              <span className="grow">{s.name} × {s.quantity}</span>
              <span>{vnd(s.unitPrice * s.quantity)}</span>
              {inHouse && (
                <button className="btn no-print" disabled={remove.isPending} onClick={() => remove.mutate(s.id)}>Xóa</button>
              )}
            </div>
          ))}
          {inHouse && (
            <div className="sd-add no-print">
              <select value={serviceId} onChange={(e) => setServiceId(e.target.value)}>
                <option value="">Chọn dịch vụ...</option>
                {catalog.data?.map((s) => <option key={s.serviceId} value={s.serviceId}>{s.name} – {vnd(s.price)}</option>)}
              </select>
              <input type="number" min={1} value={qty} onChange={(e) => setQty(+e.target.value)} style={{ width: 80 }} />
              <button className="btn primary" disabled={!serviceId || qty < 1 || add.isPending} onClick={() => add.mutate()}>
                {add.isPending ? 'Đang ghi...' : 'Thêm dịch vụ'}
              </button>
            </div>
          )}
        </section>

        <section className="sd-sec sd-bill">
          <h3>Hóa đơn</h3>
          <div><span>Tiền phòng</span><span>{vnd(r.totalAmount)}</span></div>
          <div><span>Dịch vụ</span><span>{vnd(svcSum)}</span></div>
          <div className="total"><span>Tổng cộng</span><span>{vnd(total)}</span></div>
          <div><span>Đã thu</span><span>{vnd(r.paidAmount)}</span></div>
          <div className="due"><span>Còn phải thu</span><span>{vnd(due)}</span></div>
        </section>

        <div className="sd-foot no-print">
          <button className="btn" onClick={() => window.print()}>🖨️ In hóa đơn</button>
        </div>
      </>
    )
  }

  return (
    <div className="modal-bg" onClick={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className="stay-dialog">{body}</div>
    </div>
  )
}