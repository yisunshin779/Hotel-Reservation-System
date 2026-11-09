import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AuthApi } from '../api/hotel'
import { useAuth } from '../store/auth'

const DEMOS = [
  { label: 'Khách', email: 'khach@example.com' },
  { label: 'Lễ tân', email: 'letan@example.com' },
  { label: 'Quản trị', email: 'admin@example.com' },
]

export default function LoginPage() {
  const { t } = useTranslation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPw, setShowPw] = useState(false)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const login = useAuth((s) => s.login)
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const { token, user } = await AuthApi.login(email, password)
      login(token, user)
      navigate(from ?? (user.role === 'guest' ? '/' : '/dashboard'), { replace: true })
    } catch (err) {
      setError((err as Error).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="auth-page">
      <div className="bg-photo" />
      <div className="bg-shade" />
      {Array.from({ length: 14 }).map((_, i) => (
        <span
          key={i}
          className="spark"
          style={{
            left: `${(i * 7 + 5) % 100}%`,
            animationDelay: `${(i * 0.9) % 8}s`,
            animationDuration: `${9 + (i % 5) * 2}s`,
          }}
        />
      ))}

      <div className="auth">
        <section className="auth-hero">
          <span className="orb o1" />
          <span className="orb o2" />
          <span className="orb o3" />
          <div className="hero-content">
            <div className="hero-icon">🏨</div>
            <div className="stars">
              {[0, 1, 2, 3, 4].map((i) => (
                <span key={i} style={{ animationDelay: `${0.6 + i * 0.12}s` }}>★</span>
              ))}
            </div>
            <h2>{t('Nghỉ ngơi trọn vẹn, đặt phòng dễ dàng')}</h2>
            <p>{t('Đăng nhập để quản lý đặt phòng và nhận ưu đãi dành riêng cho bạn.')}</p>
            <ul className="perks">
              <li style={{ animationDelay: '0.5s' }}>✓ {t('Phòng trống cập nhật theo thời gian thực')}</li>
              <li style={{ animationDelay: '0.7s' }}>✓ {t('Hủy miễn phí trước 48 giờ')}</li>
              <li style={{ animationDelay: '0.9s' }}>✓ {t('Mã ưu đãi cho thành viên')}</li>
            </ul>
          </div>
        </section>

        <form className="auth-form" onSubmit={handleSubmit}>
          <h1>{t('Đăng nhập')}</h1>

          <label>
            Email
            <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>

          <label>
            {t('Mật khẩu')}
            <div className="pw">
              <input
                type={showPw ? 'text' : 'password'}
                required
                minLength={8}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
              <button type="button" className="pw-toggle" onClick={() => setShowPw(!showPw)}>
                {showPw ? t('Ẩn') : t('Hiện')}
              </button>
            </div>
          </label>

          {error && <p className="err shake">{t(error)}</p>}

          <button className="btn primary shine" disabled={loading}>
            {loading ? t('Đang xử lý...') : t('Đăng nhập')}
          </button>

          <div className="auth-links">
            <Link to="/forgot-password">{t('Quên mật khẩu?')}</Link>
            <Link to="/register">{t('Tạo tài khoản')}</Link>
          </div>

          <div className="demo">
            <span className="mut">{t('Dùng tài khoản demo:')}</span>
            <div>
              {DEMOS.map((d) => (
                <button
                  type="button"
                  key={d.email}
                  className="chip"
                  onClick={() => { setEmail(d.email); setPassword('12345678') }}
                >
                  {t(d.label)}
                </button>
              ))}
            </div>
          </div>
        </form>
      </div>
    </div>
  )
}