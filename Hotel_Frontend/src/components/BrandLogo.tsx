type BrandLogoProps = { className?: string; tagline?: string }

export default function BrandLogo({ className = '', tagline }: BrandLogoProps) {
  return (
    <span className={`brand-lockup ${className}`}>
      <svg className="brand-symbol" viewBox="0 0 48 48" role="img" aria-label="Biểu tượng Sứ Hotel">
        <rect width="48" height="48" rx="15" fill="currentColor" />
        <path d="M15 14h18M18 14c.4 3.2 1.2 5.2 3 7.1l-5.4 13.2c-.8 1.9.6 4.1 2.7 4.1h11.4c2.1 0 3.5-2.2 2.7-4.1L29 21.1c1.8-1.9 2.6-3.9 3-7.1" fill="none" stroke="#fff8ef" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" />
        <path d="M18.2 31.5h11.6M21 25.5h6" fill="none" stroke="#e8b978" strokeWidth="1.8" strokeLinecap="round" />
        <circle cx="24" cy="9" r="1.5" fill="#e8b978" />
      </svg>
      <span className="brand-copy">
        <b className="brand-name">Sứ Hotel</b>
        {tagline && <small className="brand-tagline">{tagline}</small>}
      </span>
    </span>
  )
}