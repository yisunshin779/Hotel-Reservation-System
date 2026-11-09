// Bản dịch tiếng Anh. Khóa = chữ tiếng Việt trong code, giá trị = chữ tiếng Anh.
// Muốn thêm câu mới: thêm một dòng  'chữ tiếng Việt': 'English text',
export const en: Record<string, string> = {
  // ----- Menu -----
  'nav.about': 'About us',
  'nav.search': 'Search rooms',
  'nav.myBookings': 'My bookings',
  'nav.login': 'Sign in',
  'nav.logout': 'Sign out',
  'dash.rooms': 'Room map',
  'dash.reservations': 'Reservations',
  'dash.reports': 'Reports',
  'dash.pricing': 'Pricing & promotions',

  // ----- Chi nhánh (dùng ở nhiều trang) -----
  'Hà Nội – Hoàn Kiếm': 'Hanoi – Hoan Kiem',
  'Đà Nẵng – Mỹ Khê': 'Da Nang – My Khe',
  'TP.HCM – Quận 1': 'Ho Chi Minh City – District 1',

  // ----- Trang Giới thiệu -----
  'Câu chuyện của chúng tôi': 'Our story',
  'Sứ Hotel – nơi mỗi chuyến đi': 'Sứ Hotel – where every trip',
  'đều có một chỗ nghỉ chân đáng nhớ': 'finds a memorable place to rest',
  'Lấy cảm hứng từ sự tinh tế của gốm sứ Việt: giản dị, bền bỉ và ấm áp.':
    'Inspired by the refinement of Vietnamese ceramics: simple, lasting and warm.',
  'Chi nhánh': 'Branches',
  'Phòng nghỉ': 'Rooms',
  'Khách đã đặt phòng': 'Guests served',
  'Điểm đánh giá': 'Guest rating',
  'Ba điểm đến của Sứ Hotel': 'Three destinations of Sứ Hotel',
  'Giữa phố cổ, bước chân ra là hồ Gươm và những quán cà phê trứng nổi tiếng.':
    'In the Old Quarter, steps from Hoan Kiem Lake and the famous egg coffee cafés.',
  'Cách biển vài phút đi bộ, view bình minh trên bãi biển đẹp nhất hành tinh.':
    'A few minutes on foot from the beach, with sunrise views over one of the most beautiful beaches in the world.',
  'Trung tâm sôi động, thuận tiện đi lại, ăn uống và mua sắm cả ngày lẫn đêm.':
    'In the vibrant city centre, convenient for getting around, dining and shopping day and night.',
  'Khách nói gì về chúng tôi': 'What our guests say',
  'Phòng sạch sẽ, nhân viên thân thiện, đặt phòng chỉ mất vài phút.':
    'Clean rooms, friendly staff, and booking took just a few minutes.',
  'Giá rõ ràng, không phát sinh. Gia đình mình rất hài lòng với phòng Deluxe.':
    'Clear prices with no surprises. Our family loved the Deluxe room.',
  'Hủy đơn dễ dàng khi đổi lịch, đây là điều mình thích nhất.':
    'Cancelling was easy when our plans changed – that is what I like most.',
  'Minh Anh, Hà Nội': 'Minh Anh, Hanoi',
  'Quốc Bảo, Đà Nẵng': 'Quoc Bao, Da Nang',
  'Thu Hà, TP.HCM': 'Thu Ha, Ho Chi Minh City',
  'Lời khen {{n}}': 'Testimonial {{n}}',
  'Sẵn sàng cho chuyến đi tiếp theo?': 'Ready for your next trip?',
  'Chọn ngày, chọn chi nhánh, xác nhận phòng chỉ trong vài phút.':
    'Pick your dates and branch, and confirm your room in minutes.',
  'Đặt phòng ngay': 'Book now',

  // ----- Trang Tìm phòng -----
  'Tìm nơi nghỉ ngơi': 'Find the perfect place',
  'hoàn hảo cho chuyến đi của bạn': 'to rest on your trip',
  'Đặt phòng nhanh chóng, giá rõ ràng, xác nhận ngay.': 'Quick booking, clear prices, instant confirmation.',
  '-- Chọn chi nhánh --': '-- Select a branch --',
  'Nhận phòng': 'Check-in',
  'Trả phòng': 'Check-out',
  'Người lớn': 'Adults',
  'Trẻ em': 'Children',
  'Số phòng': 'Rooms',
  'Tìm phòng trống': 'Find available rooms',
  'Ngày nhận phòng không được ở quá khứ.': 'Check-in date cannot be in the past.',
  'Ngày trả phòng phải sau ngày nhận phòng.': 'Check-out must be after check-in.',
  'Không còn phòng phù hợp. Hãy thử đổi ngày hoặc số phòng.': 'No suitable rooms left. Try different dates or fewer rooms.',
  '{{n}} hạng phòng · {{nights}} đêm': '{{n}} room type(s) · {{nights}} night(s)',
  'Chỉ còn {{n}} phòng': 'Only {{n}} left',
  'Còn {{n}} phòng': '{{n}} available',
  'Tối đa {{n}} người': 'Up to {{n}} guests',
  'Từ {{price}}/đêm': 'From {{price}}/night',
  '{{nights}} đêm × {{rooms}} phòng': '{{nights}} night(s) × {{rooms}} room(s)',
  'Đặt phòng': 'Book now',
  'Vì sao chọn Sứ Hotel?': 'Why choose Sứ Hotel?',
  'Phòng trống thời gian thực': 'Real-time availability',
  'Xem ngay phòng còn trống và giá chính xác theo ngày bạn chọn.': 'See available rooms and exact prices for your dates instantly.',
  'Hủy miễn phí': 'Free cancellation',
  'Được hủy hoặc đổi đơn miễn phí trước 48 giờ nhận phòng.': 'Cancel or change your booking free of charge up to 48 hours before check-in.',
  'Ưu đãi thành viên': 'Member offers',
  'Nhập mã khuyến mãi khi đặt phòng để được giảm giá ngay.': 'Enter a promo code when booking to get an instant discount.',
  // dữ liệu hạng phòng
  'Phòng gọn gàng, đầy đủ tiện nghi cho chuyến đi ngắn.': 'A neat room with all the essentials for a short stay.',
  'Không gian rộng, tầm nhìn đẹp, phù hợp gia đình nhỏ.': 'Spacious with a lovely view, ideal for a small family.',
  'Hạng cao cấp với phòng khách riêng và dịch vụ ưu tiên.': 'Premium class with a private living room and priority service.',
  'Giường đôi': 'Double bed',
  'View thành phố': 'City view',
  'Bồn tắm': 'Bathtub',
  'Phòng khách riêng': 'Private living room',

  // ----- Trang Đăng nhập -----
  'Nghỉ ngơi trọn vẹn, đặt phòng dễ dàng': 'Rest well, book easily',
  'Đăng nhập để quản lý đặt phòng và nhận ưu đãi dành riêng cho bạn.': 'Sign in to manage your bookings and get offers made for you.',
  'Phòng trống cập nhật theo thời gian thực': 'Real-time room availability',
  'Hủy miễn phí trước 48 giờ': 'Free cancellation up to 48 hours before',
  'Mã ưu đãi cho thành viên': 'Promo codes for members',
  'Đăng nhập': 'Sign in',
  'Mật khẩu': 'Password',
  'Hiện': 'Show',
  'Ẩn': 'Hide',
  'Đang xử lý...': 'Processing...',
  'Dùng tài khoản demo:': 'Use a demo account:',
  'Khách': 'Guest',
  'Lễ tân': 'Receptionist',
  'Quản trị': 'Admin',
  'Quên mật khẩu?': 'Forgot password?',
  'Tạo tài khoản': 'Create account',
  'Thông tin đăng nhập không đúng.': 'Incorrect email or password.',
  'Không kết nối được máy chủ.': 'Cannot reach the server.',
}