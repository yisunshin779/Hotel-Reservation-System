import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { PriceApi, PromoApi } from '../../api/hotel'
import { today, vnd } from '../../lib'

export default function PricingPage() {
  const queryClient = useQueryClient()
  const prices = useQuery({ queryKey: ['prices'], queryFn: PriceApi.list })
  const promos = useQuery({ queryKey: ['promos'], queryFn: PromoApi.list })
  const [form, setForm] = useState({ code: '', percent: 10, validTo: today() })

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['promos'] })
  const create = useMutation({
    mutationFn: PromoApi.create,
    onSuccess: () => {
      refresh()
      setForm({ code: '', percent: 10, validTo: today() })
    },
  })
  const remove = useMutation({ mutationFn: PromoApi.remove, onSuccess: refresh })

  const codeOk = /^[A-Z0-9]{4,20}$/.test(form.code)
  const percentOk = form.percent >= 1 && form.percent <= 100
  const dateOk = form.validTo >= today()

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (codeOk && percentOk && dateOk) create.mutate(form)
  }

  return (
    <>
      <h1>Giá & khuyến mãi</h1>

      <div className="card wrap">
        <h2>Bảng giá theo mùa (mỗi đêm)</h2>
        {prices.isLoading && <p>Đang tải...</p>}
        {prices.error && <p className="err">{(prices.error as Error).message}</p>}
        <table>
          <thead>
            <tr><th>Hạng phòng</th><th>Ngày thường</th><th>Cuối tuần</th><th>Lễ, Tết</th></tr>
          </thead>
          <tbody>
            {prices.data?.map((p) => (
              <tr key={p.roomTypeId}>
                <td>{p.name}</td><td>{vnd(p.normal)}</td><td>{vnd(p.weekend)}</td><td>{vnd(p.holiday)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="grid two">
        <form className="card" onSubmit={handleSubmit}>
          <h2>Tạo mã khuyến mãi</h2>
          <label>
            Mã (chữ in hoa hoặc số, 4-20 ký tự)
            <input value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
            {form.code && !codeOk && <small className="err">Mã chỉ gồm chữ A-Z, số 0-9, dài 4-20 ký tự.</small>}
          </label>
          <label>
            Giảm (%)
            <input type="number" min={1} max={100} value={form.percent} onChange={(e) => setForm({ ...form, percent: +e.target.value })} />
          </label>
          <label>
            Hạn dùng đến hết ngày
            <input type="date" min={today()} value={form.validTo} onChange={(e) => setForm({ ...form, validTo: e.target.value })} />
          </label>
          {create.error && <p className="err">{(create.error as Error).message}</p>}
          <button className="btn primary" disabled={create.isPending || !codeOk || !percentOk || !dateOk}>
            {create.isPending ? 'Đang lưu...' : 'Lưu mã'}
          </button>
        </form>

        <div className="card wrap">
          <h2>Mã đang có</h2>
          {promos.data?.length === 0 && <p className="mut">Chưa có mã nào.</p>}
          <table>
            <tbody>
              {promos.data?.map((p) => (
                <tr key={p.code}>
                  <td><b>{p.code}</b></td>
                  <td>−{p.percent}%</td>
                  <td>đến {p.validTo}</td>
                  <td>
                    <button
                      className="btn"
                      disabled={remove.isPending}
                      onClick={() => confirm(`Xóa mã ${p.code}?`) && remove.mutate(p.code)}
                    >
                      Xóa
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}