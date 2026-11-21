import { useEffect, useState } from 'react'
import { useIsFetching, useIsMutating } from '@tanstack/react-query'

// Thanh tiến trình mỏng trên cùng khi đang tải hoặc lưu dữ liệu, giống Safari
export default function TopProgress() {
  // Chỉ tính truy vấn chưa có dữ liệu (tải lần đầu), để việc tự làm mới định kỳ không làm thanh nhấp nháy
  const loading = useIsFetching({ predicate: (q) => q.state.data === undefined }) > 0
  const saving = useIsMutating() > 0
  const busy = loading || saving
  const [phase, setPhase] = useState<'idle' | 'run' | 'done'>('idle')

  useEffect(() => {
    if (busy) {
      setPhase('run')
      return
    }
    setPhase((p) => (p === 'run' ? 'done' : p))
    const t = window.setTimeout(() => setPhase('idle'), 650)
    return () => window.clearTimeout(t)
  }, [busy])

  return <div className={`topbar ${phase}`} aria-hidden="true" />
}