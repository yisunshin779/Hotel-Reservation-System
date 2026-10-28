import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import './ReportsPage.css'

type ReportType = 'revenue' | 'occupancy' | 'reservations' | 'promotions'
type Metric = number | null
type Item = Record<string, string | number | boolean | null>
interface Result {
  reportType: string; from: string; to: string; groupBy: string; branchIds: number[]
  roomTypeIds: number[]; hasData: boolean; summary: Record<string, Metric>; items?: Item[]
}
interface Options {
  role: string; branches: { id: number; name: string }[]
  roomTypes: { id: number; branchId: number; name: string }[]
}
const titles: Record<ReportType, string> = {
  revenue: 'Doanh thu', occupancy: 'Công suất phòng', reservations: 'Đơn đặt phòng', promotions: 'Hiệu quả khuyến mãi',
}
const labels: Record<string, string> = {
  closedInvoiceCount: 'Hóa đơn đã chốt', refundCount: 'Khoản hoàn', roomAmount: 'Tiền phòng',
  serviceAmount: 'Dịch vụ', extrasAmount: 'Phụ thu', discountAmount: 'Giảm giá', taxAmount: 'Thuế',
  invoiceTotal: 'Tổng hóa đơn', refundTotal: 'Tiền đã hoàn', netRevenue: 'Doanh thu thuần',
  soldRoomNights: 'Đêm phòng đã bán', availableRoomNights: 'Đêm phòng khả dụng', occupancyRate: 'Công suất (%)',
  totalReservations: 'Tổng đơn', pendingPaymentCount: 'Chờ thanh toán', confirmedCount: 'Đã xác nhận',
  checkedInCount: 'Đang lưu trú', checkedOutCount: 'Đã trả phòng', cancelledCount: 'Đã hủy', noShowCount: 'No-show',
  cancellationRate: 'Tỷ lệ hủy (%)', noShowRate: 'Tỷ lệ no-show (%)', usageCount: 'Lượt sử dụng còn hiệu lực',
  reversedUsageCount: 'Lượt đã đảo', discountTotal: 'Tổng ưu đãi', periodStart: 'Đầu kỳ', branchName: 'Chi nhánh',
  code: 'Mã khuyến mãi', name: 'Tên khuyến mãi',
}
const countKeys = new Set(['closedInvoiceCount','refundCount','soldRoomNights','availableRoomNights','totalReservations',
  'pendingPaymentCount','confirmedCount','checkedInCount','checkedOutCount','cancelledCount','noShowCount','usageCount','reversedUsageCount'])
const rateKeys = new Set(['occupancyRate','cancellationRate','noShowRate'])
const cards: Record<ReportType, string[]> = {
  revenue: ['netRevenue','invoiceTotal','refundTotal'], occupancy: ['occupancyRate','soldRoomNights','availableRoomNights'],
  reservations: ['totalReservations','cancellationRate','noShowRate'], promotions: ['usageCount','discountTotal','netRevenue'],
}
const chartMetric: Record<ReportType, string> = {
  revenue: 'netRevenue', occupancy: 'occupancyRate', reservations: 'totalReservations', promotions: 'discountTotal',
}
function format(value: unknown, key = '') {
  if (value === null || value === undefined) return '—'
  if (typeof value !== 'number') return String(value)
  return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 }).format(value) +
    (rateKeys.has(key) ? '%' : countKeys.has(key) ? '' : ' ₫')
}
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
function credentials(email: string, password: string) {
  return 'Basic ' + btoa(String.fromCharCode(...new TextEncoder().encode(email + ':' + password)))
}
async function api(path: string, authorization: string, signal?: AbortSignal) {
  const response = await fetch('/api/reports/' + path, { headers: { Authorization: authorization }, signal, cache: 'no-store' })
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string }
    throw new Error(response.status === 401 ? 'Email hoặc mật khẩu không đúng.' : response.status === 403 ? 'Bạn không có quyền xem phạm vi này.' : body.message || 'Không tải được báo cáo.')
  }
  return response
}
function Chart({ rows, metric, title }: { rows: Item[]; metric: string; title: string }) {
  const [selected, setSelected] = useState<number | null>(null)
  const values = rows.map(row => Number(row[metric] || 0))
  const max = Math.max(...values.map(Math.abs), 1)
  const negative = values.some(value => value < 0)
  return <figure className="report-chart">
    <figcaption>{title} · {labels[metric]}{!countKeys.has(metric) && !rateKeys.has(metric) ? ' (VND)' : ''}</figcaption>
    <div className="report-chart-scale">{format(max, metric)}{negative ? ' / ' + format(-max, metric) : ''}</div>
    <div className="report-chart-columns" role="group" aria-label={title}>
      {rows.map((row, index) => {
        const name = String(row.periodStart || row.branchName || row.code || '')
        const value = values[index]
        return <button type="button" className="report-chart-column" key={name + index}
          aria-label={name + ': ' + format(row[metric], metric)} title={name + ': ' + format(row[metric], metric)}
          onClick={() => setSelected(index)} style={{ gridTemplateRows: negative ? '90px 1px 90px 28px' : '150px 1px 0px 28px' }}>
          <span className="report-positive"><span style={{ height: Math.max(value, 0) / max * 100 + '%' }} /></span>
          <span className="report-zero" />
          <span className="report-negative"><span style={{ height: Math.max(-value, 0) / max * 100 + '%' }} /></span>
          <span className="report-tick">{index % Math.max(1, Math.ceil(rows.length / 6)) === 0 ? name.replace(/^\d{4}-/, '') : ''}</span>
        </button>
      })}
    </div>
    <div aria-live="polite" className="report-chart-detail">{selected !== null && rows[selected]
      ? String(rows[selected].periodStart || rows[selected].branchName || rows[selected].code) + ' · ' + format(rows[selected][metric], metric)
      : 'Chọn một cột để xem giá trị.'}</div>
  </figure>
}
export default function ReportsPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [authorization, setAuthorization] = useState('')
  const [options, setOptions] = useState<Options | null>(null)
  const [type, setType] = useState<ReportType>('revenue')
  const [view, setView] = useState('by-period')
  const [from, setFrom] = useState(today.slice(0, 8) + '01')
  const [to, setTo] = useState(today)
  const [groupBy, setGroupBy] = useState('DAY')
  const [branches, setBranches] = useState<string[]>([])
  const [roomTypes, setRoomTypes] = useState<string[]>([])
  const [result, setResult] = useState<Result | null>(null)
  const [applied, setApplied] = useState<{ type: ReportType; view: string; query: string } | null>(null)
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [error, setError] = useState('')
  const pending = useRef<AbortController | null>(null)
  useEffect(() => () => pending.current?.abort(), [])
  async function connect(event: FormEvent) {
    event.preventDefault(); setError(''); setLoading(true)
    const controller = new AbortController(); pending.current?.abort(); pending.current = controller
    try {
      const auth = credentials(email, password)
      const response = await api('options', auth, controller.signal)
      const data = await response.json() as Options
      setOptions(data); setAuthorization(auth); setPassword(''); setBranches([]); setRoomTypes([])
    } catch (e) { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : 'Không kết nối được.') }
    finally { if (!controller.signal.aborted) setLoading(false) }
  }
  async function load(event: FormEvent) {
    event.preventDefault(); setError(''); setLoading(true)
    const controller = new AbortController(); pending.current?.abort(); pending.current = controller
    const params = new URLSearchParams({ from, to, groupBy })
    if (branches.length) params.set('branchIds', branches.join(','))
    if (roomTypes.length) params.set('roomTypeIds', roomTypes.join(','))
    const path = type === 'promotions' && view === 'promotions' ? 'promotions' : type + '/' + (view === 'promotions' ? 'by-period' : view)
    try {
      const response = await api(path + '?' + params, authorization, controller.signal)
      const data = await response.json() as Result
      setResult(data); setApplied({ type, view, query: params.toString() })
    } catch (e) { if (!controller.signal.aborted) { setError(e instanceof Error ? e.message : 'Không tải được.'); setResult(null); setApplied(null) } }
    finally { if (!controller.signal.aborted) setLoading(false) }
  }
  async function download() {
    if (!applied) return
    setExporting(true); setError('')
    try {
      const response = await api('export?' + applied.query + '&reportType=' + applied.type + '&view=' + applied.view, authorization)
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a'); link.href = url; link.download = 'bao-cao-' + applied.type + '.xlsx'; link.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) { setError(e instanceof Error ? e.message : 'Không xuất được Excel.') }
    finally { setExporting(false) }
  }
  const visibleTypes = options?.roomTypes.filter(room => !branches.length || branches.includes(String(room.branchId))) || []
  const currentType = applied?.type || type
  const rows = result?.items || []
  const columns = rows.length ? Object.keys(rows[0]).filter(key => !['hasData','branchId','promotionId'].includes(key)) : []
  return <div className="reports-page">
    <div className="report-heading"><div><div className="report-eyebrow">SỨ HOTEL / QUẢN TRỊ</div><h1>Báo cáo kinh doanh</h1><p>Theo dõi doanh thu, công suất và hiệu quả vận hành.</p></div>
      {options && <button className="btn" onClick={() => { pending.current?.abort(); setLoading(false); setOptions(null); setAuthorization(''); setResult(null); setApplied(null); setError('') }}>Đăng xuất báo cáo</button>}
    </div>
    {error && <p className="report-error" role="alert">{error}</p>}
    {!options ? <form className="report-login" onSubmit={connect}>
      <h2>Đăng nhập báo cáo</h2><p>Dùng tài khoản Admin hoặc Quản lý chi nhánh.</p>
      <label>Email<input type="email" autoComplete="username" required value={email} onChange={e => setEmail(e.target.value)} /></label>
      <label>Mật khẩu<input type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} /></label>
      <button className="btn primary" disabled={loading}>{loading ? 'Đang kết nối…' : 'Đăng nhập'}</button>
    </form> : <>
      <form className="report-filters" onSubmit={load}>
        <label>Loại báo cáo<select value={type} onChange={e => { const next = e.target.value as ReportType; setType(next); setView(next === 'promotions' ? 'promotions' : 'by-period') }}>{Object.entries(titles).map(([key, title]) => <option key={key} value={key}>{title}</option>)}</select></label>
        <label>Cách xem<select value={view} onChange={e => setView(e.target.value)}><option value="by-period">Theo thời gian</option><option value="by-branch">Theo chi nhánh</option>{type === 'promotions' && <option value="promotions">Theo khuyến mãi</option>}</select></label>
        <label>Từ ngày<input type="date" required value={from} onChange={e => setFrom(e.target.value)} /></label>
        <label>Đến ngày<input type="date" required min={from} value={to} onChange={e => setTo(e.target.value)} /></label>
        <label>Nhóm thời gian<select value={groupBy} onChange={e => setGroupBy(e.target.value)} disabled={view !== 'by-period'}><option value="DAY">Ngày</option><option value="WEEK">Tuần</option><option value="MONTH">Tháng</option></select></label>
        <label>Chi nhánh<select multiple aria-label="Chi nhánh" value={branches} onChange={e => { setBranches(Array.from(e.target.selectedOptions, option => option.value)); setRoomTypes([]) }}>{options.branches.map(branch => <option key={branch.id} value={branch.id}>{branch.name}</option>)}</select><small>Bỏ chọn để xem toàn bộ phạm vi được phép.</small></label>
        <label>Loại phòng<select multiple aria-label="Loại phòng" value={roomTypes} onChange={e => setRoomTypes(Array.from(e.target.selectedOptions, option => option.value))}>{visibleTypes.map(room => <option key={room.id} value={room.id}>{options.branches.find(branch => branch.id === room.branchId)?.name} · {room.name}</option>)}</select><small>Bỏ chọn để xem mọi loại phòng.</small></label>
        <div className="report-actions"><button className="btn primary" disabled={loading}>{loading ? 'Đang tải…' : 'Xem báo cáo'}</button></div>
      </form>
      {result && applied && <section className="report-results" aria-busy={loading}>
        <div className="report-heading"><div><h2>{titles[currentType]}</h2><p>{result.from} — {result.to} · {result.branchIds.length} chi nhánh · UTC+7</p></div><button type="button" className="btn" disabled={exporting || loading} onClick={download}>{exporting ? 'Đang xuất…' : 'Xuất Excel'}</button></div>
        {!result.hasData && <p className="report-empty" role="status">Không có dữ liệu trong khoảng đã chọn.</p>}
        {currentType === 'occupancy' && <p className="report-note">Sức chứa được tính theo danh mục phòng hiện tại và lịch bảo trì đã lưu.</p>}
        {result.roomTypeIds.length > 0 && ['revenue','promotions'].includes(currentType) && <p className="report-note">Số tiền theo loại phòng được phân bổ theo tỷ trọng tiền phòng của đơn.</p>}
        <div className="report-metrics">{cards[currentType].map(key => <div className="report-metric" key={key}><span>{labels[key]}</span><strong>{format(result.summary[key], key)}</strong></div>)}</div>
        {result.hasData && rows.length > 0 && <Chart key={applied.type + applied.view + applied.query} rows={rows} metric={chartMetric[currentType]} title={applied.view === 'by-branch' ? 'So sánh chi nhánh' : applied.view === 'promotions' ? 'So sánh khuyến mãi' : 'Biến động theo thời gian'} />}
        {rows.length > 0 && <div className="report-table-wrap"><table><caption>Chi tiết báo cáo</caption><thead><tr>{columns.map(key => <th key={key} scope="col">{labels[key] || key}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{columns.map(key => <td key={key}>{format(row[key], key)}</td>)}</tr>)}</tbody></table></div>}
        <details className="report-breakdown"><summary>Tất cả chỉ tiêu tổng hợp</summary><dl>{Object.entries(result.summary).map(([key, value]) => <div key={key}><dt>{labels[key] || key}</dt><dd>{format(value, key)}</dd></div>)}</dl></details>
      </section>}
    </>}
  </div>
}
