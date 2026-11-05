import { useState } from 'react'
import type { FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { AuthApi } from '../api/hotel'
import { useAuth } from '../store/auth'

export default function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
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
    <form className="card narrow" onSubmit={handleSubmit}>
      <h1>Đăng nhập</h1>
      <label>
        Email
        <input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
      </label>
      <label>
        Mật khẩu
        <input type="password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} />
      </label>
      {error && <p className="err">{error}</p>}
      <button className="btn primary" disabled={loading}>
        {loading ? 'Đang xử lý...' : 'Đăng nhập'}
      </button>
      <p className="mut">Thử: khach@ / letan@ / admin@example.com, mật khẩu 12345678</p>
    </form>
  )
}