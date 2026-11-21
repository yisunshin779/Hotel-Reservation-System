import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'

// Giữ phần tử thêm một lúc sau khi bị gỡ để kịp chạy hiệu ứng đóng (menu, cửa sổ pop-up)
export default function ExitMount({ children, ms = 320 }: { children: ReactNode; ms?: number }) {
  const present = !!children
  const [kept, setKept] = useState<ReactNode>(children)
  const [mounted, setMounted] = useState(present)

  useEffect(() => {
    if (present) {
      setKept(children)
      setMounted(true)
      return
    }
    const t = window.setTimeout(() => setMounted(false), ms)
    return () => window.clearTimeout(t)
  }, [children, present, ms])

  if (!present && !mounted) return null

  return (
    <div className={present ? undefined : 'leaving'} style={{ display: 'contents' }}>
      {present ? children : kept}
    </div>
  )
}