import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

interface Props {
  title: string
  sub?: string
  crumbs?: { to?: string; label: string }[]
  actions?: ReactNode
}

// Đầu trang con: đường dẫn, tiêu đề, mô tả ngắn
export default function PageHead({ title, sub, crumbs, actions }: Props) {
  return (
    <div className="page-head">
      {crumbs && (
        <div className="crumbs">
          {crumbs.map((c, i) => (
            <span key={c.label}>
              {i > 0 && ' › '}
              {c.to ? <Link to={c.to}>{c.label}</Link> : c.label}
            </span>
          ))}
        </div>
      )}
      <div className="ph-row">
        <div>
          <h1>{title}</h1>
          {sub && <p>{sub}</p>}
        </div>
        {actions}
      </div>
    </div>
  )
}