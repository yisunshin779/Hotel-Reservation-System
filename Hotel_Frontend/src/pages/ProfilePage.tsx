import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ProfileApi } from '../api/hotel'
import { useAuth } from '../store/auth'

const ROLE_LABEL: Record<string, string> = {
  guest: 'Khách hàng',
  receptionist: 'Lễ tân',
  manager: 'Quản lý',
  admin: 'Quản trị viên',
}

export default function ProfilePage() {
  const { t } = useTranslation()
  const { user, setUser } = useAuth()
  const [name, setName] = useState(user?.fullName ?? '')
  const [phone, setPhone] = useState(user?.phone ?? '')
  const [cur, setCur] = useState('')
  const [pw, setPw] = useState('')
  const [pw2, setPw2] = useState('')

  const phoneOk = phone === '' || /^0\d{9}$/.test(phone)
  const pwOk = pw.length >= 8 && /[A-Za-z]/.test(pw) && /\d/.test(pw)

  const save = useMutation({
    mutationFn: () => ProfileApi.update(user!.email, { fullName: name, phone }),
    onSuccess: (u) => setUser(u),
  })
  const change = useMutation({
    mutationFn: () => ProfileApi.changePassword(user!.email, cur, pw),
    onSuccess: () => { setCur(''); setPw(''); setPw2('') },
  })

  if (!user) return null

  function submitProfile(e: FormEvent) {
    e.preventDefault()
    if (name.trim() && phoneOk) save.mutate()
  }
  function submitPassword(e: FormEvent) {
    e.preventDefault()
    if (pwOk && pw === pw2) change.mutate()
  }

  return (
    <div className="home">
      <div className="home-body detail-body">
        <div className="profile-head motion-profile-head">
          <div className="avatar">{user.fullName.trim().charAt(0).toUpperCase()}</div>
          <div>
            <h1 className="detail-title" style={{ margin: 0 }}>{user.fullName}</h1>
            <span className="role-badge">{t(ROLE_LABEL[user.role] ?? user.role)}</span>
          </div>
        </div>

        <div className="profile-grid motion-stagger">
          <form className="panel motion-profile-panel" onSubmit={submitProfile}>
            <h2>{t('Thông tin cá nhân')}</h2>
            <label>
              {t('Họ và tên')}
              <input required value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <label>
              {t('Email (không đổi được)')}
              <input value={user.email} disabled />
            </label>
            <label>
              {t('Số điện thoại')}
              <input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="0901234567" />
              {phone && !phoneOk && <small className="err">{t('Gồm 10 số, bắt đầu bằng 0.')}</small>}
            </label>
            {save.error && <p className="err">{t((save.error as Error).message)}</p>}
            {save.isSuccess && <p className="ok-msg">{t('✓ Đã lưu thay đổi.')}</p>}
            <button className="btn primary shine" disabled={save.isPending || !name.trim() || !phoneOk}>
              {save.isPending ? t('Đang lưu...') : t('Lưu thay đổi')}
            </button>
          </form>

          <form className="panel motion-profile-panel" onSubmit={submitPassword}>
            <h2>{t('Đổi mật khẩu')}</h2>
            <label>
              {t('Mật khẩu hiện tại')}
              <input type="password" required value={cur} onChange={(e) => setCur(e.target.value)} />
            </label>
            <label>
              {t('Mật khẩu mới')}
              <input type="password" required value={pw} onChange={(e) => setPw(e.target.value)} />
              {pw && !pwOk && <small className="err">{t('Tối thiểu 8 ký tự, có cả chữ và số.')}</small>}
            </label>
            <label>
              {t('Nhập lại mật khẩu mới')}
              <input type="password" required value={pw2} onChange={(e) => setPw2(e.target.value)} />
              {pw2 && pw !== pw2 && <small className="err">{t('Mật khẩu nhập lại chưa khớp.')}</small>}
            </label>
            {change.error && <p className="err">{t((change.error as Error).message)}</p>}
            {change.isSuccess && <p className="ok-msg">{t('✓ Đã đổi mật khẩu.')}</p>}
            <button className="btn primary shine" disabled={change.isPending || !cur || !pwOk || pw !== pw2}>
              {change.isPending ? t('Đang xử lý...') : t('Đổi mật khẩu')}
            </button>
          </form>
        </div>
      </div>
    </div>
  )
}
