import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { AuthApi } from '../api/hotel'
import AuthShell from '../components/AuthShell'

type Step = 'email' | 'reset' | 'done'

export default function ForgotPasswordPage() {
  const [step, setStep] = useState<Step>('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [pw, setPw] = useState('')
  const [pw2, setPw2] = useState('')
  const [demoCode, setDemoCode] = useState<string | undefined>()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const pwOk = pw.length >= 8 && /[A-Za-z]/.test(pw) && /\d/.test(pw)

  async function sendCode(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const r = await AuthApi.forgot(email)
      setDemoCode(r.demoCode)
      setStep('reset')
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  async function reset(e: FormEvent) {
    e.preventDefault()
    if (!pwOk || pw !== pw2) return
    setError('')
    setLoading(true)
    try {
      await AuthApi.resetPassword(email, code, pw)
      setStep('done')
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell
      slogan="Quên mật khẩu? Không sao cả"
      sub="Nhập email đã đăng ký, chúng tôi sẽ gửi mã để bạn đặt lại mật khẩu."
      perks={['Mã có hiệu lực 10 phút', 'Không lộ thông tin tài khoản', 'Đặt lại chỉ trong vài bước']}
    >
      {step === 'email' && (
        <form className="auth-form" onSubmit={sendCode}>
          <h1>Quên mật khẩu</h1>
          <label>
            Email đã đăng ký
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          {error && <p className="err shake">{error}</p>}
          <button className="btn primary shine" disabled={loading}>
            {loading ? 'Đang gửi...' : 'Gửi mã khôi phục'}
          </button>
          <div className="auth-links"><Link to="/login">← Quay lại đăng nhập</Link></div>
        </form>
      )}

      {step === 'reset' && (
        <form className="auth-form" onSubmit={reset}>
          <h1>Đặt mật khẩu mới</h1>
          <p className="mut">
            Nếu email <b>{email}</b> có trong hệ thống, mã 6 số đã được gửi tới hộp thư.
          </p>
          {demoCode && <p className="demo-code">Mã demo (bản thật sẽ gửi qua email): <b>{demoCode}</b></p>}
          <label>
            Mã xác nhận
            <input required inputMode="numeric" maxLength={6} value={code} onChange={(e) => setCode(e.target.value)} />
          </label>
          <label>
            Mật khẩu mới
            <input type="password" required value={pw} onChange={(e) => setPw(e.target.value)} />
            {pw && !pwOk && <small className="err">Tối thiểu 8 ký tự, có cả chữ và số.</small>}
          </label>
          <label>
            Nhập lại mật khẩu mới
            <input type="password" required value={pw2} onChange={(e) => setPw2(e.target.value)} />
            {pw2 && pw !== pw2 && <small className="err">Mật khẩu nhập lại chưa khớp.</small>}
          </label>
          {error && <p className="err shake">{error}</p>}
          <button className="btn primary shine" disabled={loading || !pwOk || pw !== pw2 || code.length < 6}>
            {loading ? 'Đang lưu...' : 'Đổi mật khẩu'}
          </button>
        </form>
      )}

      {step === 'done' && (
        <div className="auth-form">
          <h1>Đã đổi mật khẩu</h1>
          <p className="mut">Bạn có thể đăng nhập bằng mật khẩu mới ngay bây giờ.</p>
          <Link to="/login" className="btn primary shine" style={{ display: 'block', textAlign: 'center' }}>
            Đến trang đăng nhập
          </Link>
        </div>
      )}
    </AuthShell>
  )
}