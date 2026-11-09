import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { AuthApi } from '../api/hotel'
import { useAuth } from '../store/auth'
import AuthShell from '../components/AuthShell'

const LEVELS = ['Rất yếu', 'Yếu', 'Trung bình', 'Khá', 'Mạnh']

export default function RegisterPage() {
  const [f, setF] = useState({ fullName: '', email: '', phone: '', password: '', confirm: '' })
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const login = useAuth((s) => s.login)
  const navigate = useNavigate()

  const set = (k: keyof typeof f, v: string) => setF({ ...f, [k]: v })

  const phoneOk = /^0\d{9}$/.test(f.phone)
  const pwOk = f.password.length >= 8 && /[A-Za-z]/.test(f.password) && /\d/.test(f.password)
  const matchOk = f.password === f.confirm
  const score = [
    f.password.length >= 8,
    /[a-z]/.test(f.password) && /[A-Z]/.test(f.password),
    /\d/.test(f.password),
    /[^A-Za-z0-9]/.test(f.password),
  ].filter(Boolean).length
  const canSubmit = f.fullName.trim() && f.email && phoneOk && pwOk && matchOk

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    setError('')
    setLoading(true)
    try {
      const { token, user } = await AuthApi.register({
        fullName: f.fullName, email: f.email, phone: f.phone, password: f.password,
      })
      login(token, user)
      navigate('/search', { replace: true })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell
      slogan="Tạo tài khoản, đặt phòng nhanh hơn"
      sub="Chỉ mất một phút để đăng ký và nhận ưu đãi dành cho thành viên."
      perks={['Lưu thông tin cho lần đặt sau', 'Xem và hủy đơn dễ dàng', 'Nhận mã ưu đãi thành viên']}
    >
      <form className="auth-form" onSubmit={handleSubmit}>
        <h1>Đăng ký</h1>

        <label>
          Họ và tên
          <input required value={f.fullName} onChange={(e) => set('fullName', e.target.value)} />
        </label>
        <label>
          Email
          <input type="email" required value={f.email} onChange={(e) => set('email', e.target.value)} />
        </label>
        <label>
          Số điện thoại
          <input required value={f.phone} onChange={(e) => set('phone', e.target.value)} />
          {f.phone && !phoneOk && <small className="err">Gồm 10 số, bắt đầu bằng 0.</small>}
        </label>
        <label>
          Mật khẩu
          <input type="password" required value={f.password} onChange={(e) => set('password', e.target.value)} />
          {f.password && (
            <>
              <div className="meter"><i className={`s${score}`} style={{ width: `${Math.max(1, score) * 25}%` }} /></div>
              <small className="hint">Độ mạnh: {LEVELS[score]}</small>
            </>
          )}
          {f.password && !pwOk && <small className="err">Tối thiểu 8 ký tự, có cả chữ và số.</small>}
        </label>
        <label>
          Nhập lại mật khẩu
          <input type="password" required value={f.confirm} onChange={(e) => set('confirm', e.target.value)} />
          {f.confirm && !matchOk && <small className="err">Mật khẩu nhập lại chưa khớp.</small>}
        </label>

        {error && <p className="err shake">{error}</p>}

        <button className="btn primary shine" disabled={loading || !canSubmit}>
          {loading ? 'Đang tạo tài khoản...' : 'Tạo tài khoản'}
        </button>

        <div className="auth-links">
          <span className="mut">Đã có tài khoản?</span>
          <Link to="/login">Đăng nhập</Link>
        </div>
      </form>
    </AuthShell>
  )
}