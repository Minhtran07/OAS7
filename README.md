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

---

# 🚀 Hướng dẫn chạy dự án

## Yêu cầu môi trường

- **Java 21** (JDK, không phải JRE). Kiểm tra: `java -version`
- **Maven 3.8+**. Kiểm tra: `mvn -version`
- **Port 3667** phải được mở trên máy chạy server (hoặc cấu hình port khác, xem bên dưới).

## 1. Chạy nhanh trên 1 máy (Single-machine, dành cho dev)

Mở **2 Terminal riêng biệt** trong thư mục gốc dự án:

**Terminal 1 — Server:**
```bash
# Mac/Linux
./run-server.sh

# Windows
run-server.bat
```

**Terminal 2 — Client:**
```bash
# Mac/Linux
./run-client.sh

# Windows
run-client.bat
```

Mỗi lần muốn chạy thêm 1 client (test nhiều tài khoản), chỉ cần mở thêm 1 Terminal và chạy lại `run-client`.

> **Lưu ý:** Nếu file `.sh` báo "Permission denied", chạy `chmod +x run-server.sh run-client.sh` trước.

---

## 2. Chạy trên LAN (nhiều máy cùng mạng) ⭐

Đây là chế độ khuyến nghị khi team cần đồng bộ dữ liệu (cả team cùng nhìn thấy danh sách item, phiên đấu giá giống nhau).

### Kiến trúc

```
   ┌──────────────────────┐
   │  Máy A (Server)      │
   │  - Chạy MainServer   │
   │  - Chứa DB SQLite    │
   │  - IP: 192.168.1.50  │◄──── Cả team kết nối đến đây
   └──────────┬───────────┘
              │  TCP :3667 (LAN)
      ┌───────┼────────┐
      │       │        │
   ┌──▼──┐ ┌──▼──┐ ┌──▼──┐
   │Máy B│ │Máy C│ │Máy D│   ← Client
   └─────┘ └─────┘ └─────┘
```

**Một máy duy nhất** chạy server (máy này chứa file DB `auction.db`). Tất cả thành viên còn lại chỉ chạy **client** và trỏ đến IP của máy server.

### Bước 1 — Trên máy chạy Server

```bash
./run-server.sh        # hoặc run-server.bat trên Windows
```

Khi server khởi động, log sẽ in ra các IP LAN mà nó đang lắng nghe, ví dụ:

```
Các địa chỉ IP máy chủ đang lắng nghe (dùng IP này cho client ở máy khác):
  → 192.168.1.50:3667  (en0)
  → 10.0.0.15:3667     (wlan0)
```

Ghi nhớ địa chỉ IP phù hợp với mạng LAN mà team đang dùng (thường là `192.168.x.x`).

**Lưu ý Firewall:**
- **macOS:** `System Settings → Network → Firewall` → cho phép `java` nhận kết nối đến.
- **Windows:** Khi chạy lần đầu, Windows Defender sẽ hỏi → chọn **Allow** cho Private Network.
- **Linux:** `sudo ufw allow 3667/tcp`

### Bước 2 — Trên mỗi máy Client

Set biến môi trường `AUCTION_SERVER_HOST` trước khi chạy client:

**Mac/Linux:**
```bash
export AUCTION_SERVER_HOST=192.168.1.50
./run-client.sh
```

**Windows (CMD):**
```cmd
set AUCTION_SERVER_HOST=192.168.1.50
run-client.bat
```

**Windows (PowerShell):**
```powershell
$env:AUCTION_SERVER_HOST="192.168.1.50"
.\run-client.bat
```

### Cách khác — Dùng file cấu hình (không cần set env mỗi lần)

Tạo file `~/.auction-client.properties` (ở thư mục home của user):

```properties
host=192.168.1.50
port=3667
```

Sau đó chỉ cần `./run-client.sh` như bình thường, client sẽ tự đọc cấu hình.

### Thứ tự ưu tiên khi client resolve địa chỉ server

1. JVM system property: `-Dauction.server.host=...`
2. Biến môi trường: `AUCTION_SERVER_HOST`
3. File cấu hình: `~/.auction-client.properties`
4. Mặc định: `localhost:3667`

---

## 3. Đổi port (nếu 3667 đã bị chiếm)

**Server:**
```bash
AUCTION_SERVER_PORT=4000 ./run-server.sh
```

**Client** (nhớ trỏ cùng port):
```bash
AUCTION_SERVER_HOST=192.168.1.50 AUCTION_SERVER_PORT=4000 ./run-client.sh
```

---

## 4. Troubleshooting

| Triệu chứng | Nguyên nhân | Cách xử lý |
|---|---|---|
| `Connection refused` | Server chưa chạy hoặc sai IP | Kiểm tra server đã khởi động, kiểm tra `AUCTION_SERVER_HOST` |
| `Connection timed out` | Firewall chặn port 3667 | Mở firewall trên máy server |
| Client kết nối được nhưng không thấy item mới | Vẫn đang dùng DB local cũ | Đảm bảo client KHÔNG set host = `localhost`; phải trỏ đến IP của máy server |
| Server khởi động lỗi `BindException: Address already in use` | Port 3667 đã bị chiếm | Đổi port bằng `AUCTION_SERVER_PORT=...` |
| Seller đăng nhập không thấy nút đặt giá | Đây là hành vi ĐÚNG | Seller chỉ được xem, không được đấu giá (Chế độ theo dõi) |

---

## 5. Phân quyền tài khoản

| Role | Được làm gì |
|---|---|
| **ADMIN** | Phê duyệt item, quản lý user |
| **SELLER** | Đăng item, xem phiên đấu giá (read-only, KHÔNG được đặt giá) |
| **BIDDER** | Đặt giá thủ công, đăng ký auto-bid |

Khi Seller mở phiên đấu giá, UI sẽ ẩn form đặt giá và hiển thị badge **"👁 Chế độ theo dõi — Seller không thể đặt giá"**.
