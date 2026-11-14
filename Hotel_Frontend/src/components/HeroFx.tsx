// Hiệu ứng lung linh dùng chung cho banner ảnh (đặt trong <section className="home-hero hero-photo ...">)
const BOKEH = Array.from({ length: 12 }, (_, i) => ({
  left: (i * 37 + 8) % 100,
  size: 36 + ((i * 29) % 70),
  delay: (i * 1.3) % 9,
  dur: 12 + (i % 5) * 3,
}))

const STARS = Array.from({ length: 18 }, (_, i) => ({
  left: (i * 53 + 7) % 100,
  top: (i * 31 + 11) % 78,
  size: 8 + ((i * 7) % 14),
  delay: (i * 0.7) % 5,
  dur: 2.4 + (i % 4) * 0.8,
}))

export default function HeroFx() {
  return (
    <>
      <div className="hero-bg" />
      <div className="hero-shade" />
      <div className="rays" />
      <div className="sweep" />

      {BOKEH.map((b, i) => (
        <span
          key={`b${i}`}
          className="fx-bokeh"
          style={{
            left: `${b.left}%`,
            width: b.size,
            height: b.size,
            animationDelay: `${b.delay}s`,
            animationDuration: `${b.dur}s`,
          }}
        />
      ))}

      {STARS.map((s, i) => (
        <span
          key={`s${i}`}
          className="fx-star"
          style={{
            left: `${s.left}%`,
            top: `${s.top}%`,
            width: s.size,
            height: s.size,
            animationDelay: `${s.delay}s`,
            animationDuration: `${s.dur}s`,
          }}
        />
      ))}
    </>
  )
}