import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import ExitMount from './ExitMount'

interface Props {
  label: ReactNode
  align?: 'left' | 'right'
  children: ReactNode
}

// Menu thả xuống: bấm để mở, bấm ra ngoài hoặc nhấn Esc để đóng, bấm một mục thì tự đóng
export default function Dropdown({ label, align = 'left', children }: Props) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  return (
    <div className="dd" ref={ref}>
      <button
        type="button"
        className={`dd-btn ${open ? 'open' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        {label}
        <span className="caret">▾</span>
      </button>
      <ExitMount ms={200}>
        {open && (
          <div className={`dd-menu ${align === 'right' ? 'right' : ''}`} onClick={() => setOpen(false)}>
            {children}
          </div>
        )}
      </ExitMount>
    </div>
  )
}