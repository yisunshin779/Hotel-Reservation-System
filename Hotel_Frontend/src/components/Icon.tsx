import type { ReactNode } from 'react'

const PATHS: Record<string, ReactNode> = {
  building: (<><path d="M5 21V4l7-2 7 2v17" /><path d="M9 21v-4h6v4" /><path d="M9 8h.01M15 8h.01M9 12h.01M15 12h.01" /></>),
  bed: (<><path d="M3 19V6" /><path d="M3 15h18v4" /><path d="M21 15v-3a3 3 0 0 0-3-3h-8v6" /><circle cx="6.5" cy="11" r="1.6" /></>),
  info: (<><circle cx="12" cy="12" r="9" /><path d="M12 11v5" /><path d="M12 8h.01" /></>),
  pin: (<><path d="M12 21s-7-6-7-11a7 7 0 0 1 14 0c0 5-7 11-7 11z" /><circle cx="12" cy="10" r="2.5" /></>),
  phone: (<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z" />),
  check: (<path d="M5 12l5 5 9-10" />),
}

export default function Icon({ name, size = 20 }: { name: string; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth={1.8} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {PATHS[name]}
    </svg>
  )
}