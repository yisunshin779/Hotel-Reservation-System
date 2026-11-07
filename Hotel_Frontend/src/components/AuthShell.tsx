import type { ReactNode } from 'react'

interface Props {
  slogan: string
  sub: string
  perks: string[]
  children: ReactNode
}

// Khung dùng chung cho trang đăng ký và quên mật khẩu: nền ảnh bên ngoài, giới thiệu bên trái, form bên phải
export default function AuthShell({ slogan, sub, perks, children }: Props) {
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
            <h2>{slogan}</h2>
            <p>{sub}</p>
            <ul className="perks">
              {perks.map((p, i) => (
                <li key={p} style={{ animationDelay: `${0.5 + i * 0.2}s` }}>✓ {p}</li>
              ))}
            </ul>
          </div>
        </section>
        {children}
      </div>
    </div>
  )
}