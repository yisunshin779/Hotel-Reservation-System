import { useState } from 'react'

interface Props {
  value: number
  onChange?: (n: number) => void // có onChange thì bấm chọn được, không có thì chỉ để xem
  size?: number
}

export default function Stars({ value, onChange, size = 18 }: Props) {
  const [hover, setHover] = useState(0)
  const shown = hover || value

  return (
    <span className="stars-rt" style={{ fontSize: size }} aria-label={`${value} trên 5 sao`}>
      {[1, 2, 3, 4, 5].map((n) =>
        onChange ? (
          <button
            type="button"
            key={n}
            className={n <= shown ? 'on' : ''}
            onMouseEnter={() => setHover(n)}
            onMouseLeave={() => setHover(0)}
            onClick={() => onChange(n)}
            aria-label={`${n} sao`}
          >
            ★
          </button>
        ) : (
          <span key={n} className={n <= Math.round(value) ? 'on' : ''}>★</span>
        )
      )}
    </span>
  )
}