import { useEffect, useRef } from 'react'
import { Outlet, useLocation } from 'react-router-dom'

// Những khối sẽ hiện dần khi cuộn tới (không đụng vào các phần tử đã có animation riêng)
const REVEAL =
  '.ecard, .bcard, .stat-item, .panel:not(.book-box), .card:not(.skeleton), .quote-box, .cta, .section-title, .specs > div'

const depth = (p: string) => p.split('/').filter(Boolean).length

export default function PageTransition() {
  const { pathname } = useLocation()
  const boxRef = useRef<HTMLDivElement>(null)
  const prev = useRef(pathname)
  const dir = useRef('pt-fade')

  // Đi sâu hơn thì trượt từ phải, quay lại thì trượt từ trái, ngang cấp thì chỉ hiện dần
  if (prev.current !== pathname) {
    const a = depth(prev.current)
    const b = depth(pathname)
    dir.current = b > a ? 'pt-fwd' : b < a ? 'pt-back' : 'pt-fade'
    prev.current = pathname
  }

  // Trang mới luôn bắt đầu từ đầu, và các khối hiện dần khi cuộn tới
  useEffect(() => {
    window.scrollTo(0, 0)
    const root = boxRef.current
    if (!root) return

    const timers: number[] = []
    const io = new IntersectionObserver(
      (entries) => {
        let k = 0
        entries.forEach((en) => {
          if (!en.isIntersecting) return
          const el = en.target as HTMLElement
          io.unobserve(el)
          el.style.transitionDelay = `${Math.min(k++, 6) * 70}ms`
          requestAnimationFrame(() => el.classList.add('rv-in'))
          // Xong thì gỡ hết để không ảnh hưởng hiệu ứng rê chuột của phần tử
          timers.push(
            window.setTimeout(() => {
              el.classList.remove('rv-pre', 'rv-in')
              el.style.transitionDelay = ''
            }, 1400)
          )
        })
      },
      { threshold: 0.08, rootMargin: '0px 0px -40px 0px' }
    )

    root.querySelectorAll<HTMLElement>(REVEAL).forEach((el) => {
      el.classList.add('rv-pre')
      io.observe(el)
    })

    return () => {
      io.disconnect()
      timers.forEach((t) => clearTimeout(t))
    }
  }, [pathname])

  // Parallax banner và bóng đổ thanh menu khi cuộn
  useEffect(() => {
    const hero = boxRef.current?.querySelector<HTMLElement>('.home-hero')
    let raf = 0
    const onScroll = () => {
      if (raf) return
      raf = requestAnimationFrame(() => {
        raf = 0
        const y = window.scrollY
        document.documentElement.classList.toggle('scrolled', y > 8)
        hero?.style.setProperty('--sy', String(Math.min(y, 500)))
      })
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      window.removeEventListener('scroll', onScroll)
      cancelAnimationFrame(raf)
    }
  }, [pathname])

  return (
    <div ref={boxRef} key={pathname} className={`pt ${dir.current}`}>
      <Outlet />
    </div>
  )
}