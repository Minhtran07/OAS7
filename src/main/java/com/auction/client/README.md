# Các mục sẽ làm giao diện (Khả năng)
## 1. Authentication
- **Đăng nhập / Đăng ký -> Authentication.fxml**
- **Login.fxml**
    - **Nhập username/email**
    - **Nhập password (dạng ẩn)**
    - **Nút đăng nhập**

- **Register.fxml**
    - **Nhập thông tin (Họ tên, Email, SDT)**
    - **Đăng ký user mới(Bidder/Seller)**
    - **Nút tạo tài khoản**

## 2. Trang chi tiết sản phẩm

- **AuctionList.fxml**
- **Hiển thị**
    - **ImageView (Có icon/chức năng Zoom)**
    - **Thông tin item (ảnh, mô tả, giá khởi điểm, khoảng giá)**
    - **Thời gian bắt đầu-kết thúc**
    - **Place bid (chuyển sang LiveBid/ Register to bid đối với chưa đăng nhập)**
- **LiveBidding.fxml**
    - **Đồng hồ đếm ngược realtime**
    - **Label: Giá cược cao nhất hiện tại**
    - **TextField/Spinner: Nhập số tiền muốn bid (gợi ý sẵn bước giá hợp lệ)/Auto-Bid**
    - **Place Bid**
    - **List View: Danh sách lịch sử bid (Xếp hạng, tên Bidder, thời gian, số tiền,...)**

## 3. Trang danh sách đấu giá (Home/ Auction List)

- **Home.fxml**
    - **Thanh điều hướng với icon: My Account - How to Buy/Sell**
    - **Thanh điều hướng dưới: icon web - Auctions (Aution List) - Buy Now - Sell - ô Search - mục yêu thích - giỏ hàng**
    - **Banner Slider: Các phiên đấu giá nổi bật + Ảnh, Sản phẩm bán nổi bật + ảnh**
- **SearchFilter.fxml: Trang kết quả tìm kiếm**
    - **Giữ các thanh điều hướng**
    - **Panel bộ lọc: Các checkbox (loại tài sản,nghệ sĩ), thời gian diễn ra, khoảng giá, vị trí**
    - **Sort by**
    - **Lưới hiển thị kết quả**
## 4 . Trang cá nhân (User Dashboard)
- **Bảng điều khiển người dùng -> UserDashboard.fxml**
    - **Icon Avatar & Tên người dùng**
    - **Sidebar menu: Hồ sơ, Lịch sử giao dịch, Hạng thẻ thành viên**
    - **TableView: Bảng thống kê trạng thái các vật phẩm đang bid/đang ký gửi**
- **Danh sách yêu thích -> Favorites.fxml**
    - **Icon Trái tim (Heart) trạng thái Active**
    - **Danh sách vật phẩm (ListView/GridPane)**
    - **Nút Xóa/Icon thùng rác cho từng vật phẩm**
- **Giỏ hàng -> ShoppingCart.fxml**
    - TableView: Liệt kê item đang có (Kèm icon thumbnail)
    - Nút Xóa (Icon dấu X/Thùng rác)
    - Label tổng kết: Tạm tính, Thuế, Tổng tiền
    - Nút "Proceed to Checkout"
- **Thanh toán -> Checkout.fxml**
    - Form địa chỉ: TextFields nhập thông tin người nhận
    - Form thanh toán: Radio buttons chọn phương thức (Kèm icon Visa, Mastercard, Paypal)
    - Nút "Confirm Payment"
## 5. Trang tạo sản phẩm (Seller)
- **Giao diện Yêu cầu Định giá / Ký gửi -> SellEstimate.fxml**
    - Upload Area: Nút/Khu vực kéo thả ảnh (Kèm icon Upload/Camera)
    - TextFields: Nhập kích thước, tên nghệ sĩ, chất liệu
    - TextArea: Nhập nguồn gốc/câu chuyện vật phẩm
    - Nút "Submit Request"

## 6. Trang quản lý đấu giá (Seller/Admin)
- **Danh sách Phiên đấu giá sắp tới -> UpcomingAuctions.fxml**
    - Lịch (DatePicker/Calendar icon) chọn ngày
    - Danh sách các sự kiện đấu giá (Card view chứa ảnh banner, ngày giờ, địa điểm)
    - Nút "View Catalogue"
- **Lịch sử / Kết quả đấu giá -> AuctionResults.fxml**
    - Bộ lọc thời gian (Năm/Tháng)
    - TableView hiển thị kết quả: Số Lot, Tên vật phẩm, Giá gõ búa (Hammer Price)
- **Catalog Phiên đấu giá -> AuctionCatalogue.fxml**
    - Label: Tên sự kiện & Đồng hồ đếm ngược (Countdown Timer) đến lúc bắt đầu
    - Lưới hiển thị (GridPane): Liệt kê toàn bộ các Lot trong phiên

## 7. Popup / Alert (không phải page riêng)
- **Log hệ thống thông báo realtime (Ai vừa bid) (Mở rộng từ LiveBidding.fxml để sử dụng chung cho các thông báo hệ thống)**
- **Thông báo xác nhận / Cảnh báo: Lỗi nhập liệu, xác nhận đặt giá, thông báo kết quả giao dịch**

## 8. Khối Mua Ngay & Dịch Vụ (Bổ sung từ dữ liệu)
- **Danh mục Sản phẩm -> RetailCategory.fxml**
    - Sidebar menu: Nhóm hàng (Đồng hồ, Túi xách, Trang sức...)
    - GridPane: Thẻ sản phẩm (Ảnh, Tên, Label Giá cố định)
- **Chi tiết Sản phẩm Mua ngay -> RetailProductDetail.fxml**
    - ImageView: Ảnh sản phẩm (Gallery slider)
    - Text: Mô tả vật liệu, kích thước, tình trạng
    - Nút "Add to Cart" (Kèm icon Giỏ hàng có dấu cộng)
- **Hướng dẫn Mua / Bán -> Guide.fxml**
    - Accordion/TitledPane: Các khối nội dung có thể click xổ xuống (Step 1, Step 2...)
    - Icons minh họa cho từng bước (Icon búa đấu giá, Icon đóng gói hàng)
- **Danh sách Dịch vụ -> Services.fxml**
    - GridPane: Các thẻ dịch vụ (Định giá, Bảo quản, Vay thế chấp nghệ thuật)Icons đại diện (Icon Chứng chỉ/Document, Icon Khiên/Shield bảo mật)
    - Nút "Contact Us" (Kèm icon Email/Điện thoại)

# Tối thiểu để chạy được

## Login, Auction List, Auction Detail (bid realtime), Create Auction (optional)  
