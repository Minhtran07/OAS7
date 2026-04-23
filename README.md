# Đỗ Ngọc Minh
## Nhiệm vụ

*Database*
- *Thiết kế lược đồ Cơ sở dữ liệu (Database Schema) và đảm bảo các quy tắc chuẩn hóa (Normalization) bằng SQLite.*
- *Viết các lớp Data Access Object (UserDAO, AuctionDAO,...) thực thi các câu lệnh SQL (CRUD, JOIN).*
- *Tích hợp PreparedStatement để bảo mật hệ thống khỏi các cuộc tấn công SQL Injection.*

# Đoàn Nhật Phương
 
## Nhiệm vụ

*Server Core & Network Routing*
- *Xây dựng nhân máy chủ (MainServer) lắng nghe tại cổng 3667 và quản lý ExecutorService (ThreadPool).*
- *Viết luồng xử lý độc lập (ClientHandler) cho từng khách hàng kết nối tới.*
- *Thiết kế *Bộ định tuyến (Router): Đọc chuỗi JSON từ Client, phân loại các lệnh (như LOGIN, REGISTER, PLACE_BID) và điều phối xuống lớp Database hoặc tầng Nghiệp vụ.**
- *Thiết kế kiến trúc AuctionManager (Singleton) điều hành luật đấu giá, quản lý trạng thái thời gian thực và đảm bảo tính đồng bộ đa luồng.*


# Đào Việt Anh

## Nhiệm vụ

*Frontend & Client-side*
- *Thiết kế và lập trình toàn bộ Giao diện người dùng (UI) cho App.*
- *Viết logic bắt sự kiện (Click, Input) từ người dùng.*
- *Đóng gói dữ liệu nhập vào thành các đối tượng Request (JSON) và gửi qua cáp mạng.*
- *Lắng nghe Response từ Server, giải mã JSON và hiển thị thông báo/cập nhật màn hình tương ứng.*

# Nguyễn Vĩnh Hoàng

## Nhiệm vụ

*Model & Tester*

- *Xây dựng hệ thống các Lớp thực thể (Models) tuân thủ tính Kế thừa (VD: User $\rightarrow$ Bidder, Seller).*

- *Kiểm tra yêu cầu có rõ ràng không, tìm và phát hiện yêu cầu mơ hồ, thiếu, mâu thuẫn, ...*
