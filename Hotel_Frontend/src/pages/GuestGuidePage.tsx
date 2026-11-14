import { Link } from 'react-router-dom'
import PageHead from '../components/PageHead'

const STEPS = [
  { title: 'Tìm phòng', text: 'Chọn chi nhánh, ngày nhận và trả phòng, số khách và số phòng. Kết quả sẽ cho biết hạng phòng phù hợp và tổng tiền dự kiến.', to: '/search', action: 'Tìm phòng' },
  { title: 'Xem chi tiết', text: 'Mở hạng phòng để xem sức chứa, tiện nghi và thông tin lưu trú trước khi đặt.', to: '/rooms', action: 'Xem hạng phòng' },
  { title: 'Đặt và theo dõi', text: 'Đăng nhập để tạo đơn. Sau đó xem trạng thái, tiếp tục thanh toán hoặc hủy đơn trong mục Đơn của tôi.', to: '/my-bookings', action: 'Đơn của tôi' },
]

const FAQ = [
  { question: 'Tôi xem đơn đã đặt ở đâu?', answer: 'Đăng nhập bằng tài khoản dùng khi đặt phòng, rồi mở Đơn của tôi từ thanh điều hướng hoặc menu tài khoản.' },
  { question: 'Tôi có thể đặt nhiều phòng không?', answer: 'Có. Trang tìm phòng cho phép chọn số phòng trong một lần tìm; hãy kiểm tra số phòng còn trống ở kết quả trước khi đặt.' },
  { question: 'Giá hiển thị đã gồm những gì?', answer: 'Trang tìm phòng hiển thị tổng tiền dự kiến theo ngày ở và số phòng. Số tiền chính thức được thể hiện khi tạo đơn; các dịch vụ phát sinh trong kỳ ở được tính riêng.' },
  { question: 'Tôi có thể hủy đơn như thế nào?', answer: 'Mở Đơn của tôi và chọn Hủy ở đơn còn cho phép hủy. Hãy kiểm tra điều kiện và khoản hoàn tiền với chi nhánh trước khi xác nhận, vì chính sách hủy chưa được công bố trên hệ thống.' },
  { question: 'Cần hỗ trợ khi đang lưu trú?', answer: 'Hãy liên hệ lễ tân tại chi nhánh nơi bạn đang ở. Thông tin chi nhánh và số điện thoại được hiển thị ở trang Chi nhánh.' },
]

export default function GuestGuidePage() {
  return (
    <div className="guest-guide">
      <PageHead title="Hướng dẫn & trợ giúp" sub="Các bước đặt phòng và câu trả lời nhanh cho chuyến đi của bạn."
        crumbs={[{ to: '/', label: 'Trang chủ' }, { label: 'Hướng dẫn & trợ giúp' }]} />
      <div className="guide-jump"><a href="#dat-phong">Cách đặt phòng</a><a href="#cau-hoi">Câu hỏi thường gặp</a><Link to="/my-bookings">Đơn của tôi →</Link></div>
      <section id="dat-phong" className="guide-section">
        <h2>Đặt phòng trong ba bước</h2>
        <div className="guide-steps">{STEPS.map((step, index) => <article key={step.title}>
          <span>0{index + 1}</span><h3>{step.title}</h3><p>{step.text}</p><Link to={step.to}>{step.action} →</Link>
        </article>)}</div>
      </section>
      <section id="cau-hoi" className="guide-section">
        <h2>Câu hỏi thường gặp</h2>
        <div className="guide-faq">{FAQ.map((item) => <details key={item.question}>
          <summary>{item.question}</summary><p>{item.answer}</p>
        </details>)}</div>
      </section>
      <div className="guide-contact"><div><h2>Bạn muốn biết thêm về một chi nhánh?</h2><p>Xem địa chỉ và thông tin liên hệ của từng nơi trước khi lên đường.</p></div><Link to="/branches" className="btn primary">Xem chi nhánh</Link></div>
    </div>
  )
}
