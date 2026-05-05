# Cơ chế xử lý đồng thời cho Auto-Bid

> Tài liệu này giải thích vì sao hệ thống đấu giá có thể xử lý nhiều client cùng đặt giá / cùng đăng ký auto-bid trên cùng một phiên mà không bị mất bid (lost update), không bị lệch RAM/DB, và không bị deadlock giữa các phiên.

## 1. Bức tranh tổng thể

```
        ┌──────────────────────────────────────────┐
        │            MainServer (port 3667)         │
        │   ExecutorService → ClientHandler thread  │
        └──────────────────────────────────────────┘
              │            │            │
        ┌─────▼────┐  ┌────▼─────┐ ┌────▼─────┐
        │Client 1  │  │Client 2  │ │Client N  │
        │(Bidder)  │  │(Bidder)  │ │(Bidder)  │
        └──────────┘  └──────────┘ └──────────┘

         Mỗi client = 1 thread riêng. Tất cả thread cùng truy cập:
         AuctionManager (Singleton) — RAM state thật của các phiên
         AuctionDAO     — SQLite DB
         BidEventBus    — kênh push để broadcast realtime
```

Mỗi `ClientHandler` chạy trong một thread riêng do `ExecutorService` cấp. Khi 2 client đồng thời gửi `PLACE_BID` hoặc `AUTO_BID` cho cùng một phiên, **2 thread sẽ tranh nhau cùng một vùng nhớ** trong `AuctionManager`. Nếu không có cơ chế bảo vệ, sẽ xảy ra:

- **Lost update**: cả hai thread đọc `currentPrice = 5,000,000`, cả hai cộng `+100,000` rồi ghi `5,100,000` → bid của một bên biến mất.
- **RAM ≠ DB**: thread A update RAM xong, thread B đè RAM trước khi A kịp ghi DB → DB ghi nhận giá của B nhưng RAM lại là giá của A.
- **Push event lệch thứ tự**: hai broadcast chạy song song, client nhận event không theo thứ tự bid thực tế.

Hệ thống xử lý các vấn đề này bằng **3 lớp đảm bảo** trình bày ở mục 2 → 4.

## 2. Lớp 1 — Lock per-auction (mutual exclusion)

`AuctionManager.java` cấp **một `ReentrantLock` riêng cho mỗi phiên**:

```java
private final Map<Integer, ReentrantLock> auctionLocks = new ConcurrentHashMap<>();

public void addAuction(int auctionId, Auction auction) {
    activeAuctions.put(auctionId, auction);
    auctionLocks.putIfAbsent(auctionId, new ReentrantLock());
    autoBidQueues.putIfAbsent(auctionId, new PriorityQueue<>(AutoBidEntry.COMPARATOR));
}
```

Mọi thao tác sửa state phiên (`placeBid`, `registerAutoBid`, `triggerAutoBids`, `snapshot`) đều bọc trong:

```java
ReentrantLock lock = auctionLocks.get(auctionId);
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();
}
```

### Tại sao lock per-auction thay vì global lock?

- **Global lock** (`synchronized` trên cả AuctionManager) sẽ chặn TẤT CẢ phiên: client đặt giá phiên #1 phải chờ client đặt giá phiên #2 — hoàn toàn không cần thiết vì 2 phiên độc lập.
- **Per-auction lock** chỉ chặn các thao tác trên CÙNG một phiên. Phiên khác vẫn chạy song song → throughput cao hơn nhiều.

### Tại sao `ReentrantLock` thay vì `synchronized`?

- `ReentrantLock` re-entrant: trong cùng thread, gọi đệ quy `triggerAutoBids` từ `placeBid` không bị deadlock.
- Có thể `tryLock(timeout)` nếu sau này muốn detect contention.
- Có `getQueueLength()` cho monitoring.

### Tại sao `ConcurrentHashMap`?

`auctionLocks`, `activeAuctions`, `autoBidQueues` đều là `ConcurrentHashMap`. Khi seller tạo phiên mới (`addAuction`) trong khi 2 client đang bid phiên cũ, các thread đó cùng `put`/`get` map. `ConcurrentHashMap` đảm bảo các thao tác này thread-safe mà không cần lock toàn map (nội bộ nó dùng striped locking).

## 3. Lớp 2 — Atomic bid (RAM + DB trong cùng critical section)

Vấn đề kinh điển: nếu update RAM và ghi DB ở 2 nơi tách biệt thì khi DB fail, RAM đã thay đổi → không thể rollback đồng nhất.

`placeBid` của hệ thống áp dụng pattern **save-state → update RAM → write DB → rollback nếu fail** trong cùng một lock:

```java
public boolean placeBid(int auctionId, Bidder bidder, BigDecimal bidAmount) {
    lock.lock();
    try {
        Auction auction = activeAuctions.get(auctionId);
        if (auction.getStatus() != Role.RUNNING) return false;

        // 1. Lưu state cũ — sẵn sàng rollback
        BigDecimal prevPrice  = auction.getCurrentPrice();
        Bidder     prevWinner = auction.getCurrentWinner();
        int        prevWinId  = auction.getHighestBidderId();

        // 2. Update RAM
        if (!auction.updateBid(bidder, bidAmount)) return false;

        // 3. Ghi DB ngay — KHÔNG RA KHỎI LOCK
        boolean dbOk = auctionDAO.recordBid(auctionId, bidder.getId(), ...)
                    && auctionDAO.updateCurrentPrice(auctionId, ...);

        // 4. DB fail → rollback RAM, KHÔNG broadcast
        if (!dbOk) {
            auction.setCurrentPrice(prevPrice);
            auction.setCurrentWinner(prevWinner);
            auction.setHighestBidderId(prevWinId);
            return false;
        }

        // 5. Anti-snipe + broadcast + auto-bid trigger
        checkAntiSnipe(auctionId, auction);
        BidEventBus.getInstance().broadcast(...);
        triggerAutoBids(auctionId, auction, bidder.getId());

        return true;
    } finally {
        lock.unlock();
    }
}
```

Tính chất quan trọng:

- DB write nằm **bên trong** lock → 2 client không thể vượt qua nhau giữa "update RAM" và "ghi DB".
- Nếu DB fail, RAM được khôi phục về state trước bid. Client nhận `false` → UI báo lỗi → không có push event sai → các client khác không thấy bid ma.
- Push event chỉ phát SAU KHI cả RAM và DB đều OK → các client xem trận đấu chỉ thấy bid hợp lệ.

### Tại sao broadcast trong lock không gây nghẽn?

`BidEventBus.broadcast` ghi PrintWriter (network I/O). Có thể tưởng vài chục client đăng ký sẽ chậm. Thực tế:

- Mỗi `out.println(...)` chỉ ghi vào TCP send buffer của OS, không chờ ACK của client → cực nhanh (microseconds).
- Có `synchronized(out)` để tránh interleave với reply của `sendRequest` chạy trên thread khác cùng socket.
- Nếu thật sự muốn tách network khỏi critical section, có thể đẩy broadcast vào executor — nhưng phức tạp hoá thứ tự event mà không cần thiết với quy mô hiện tại.

## 4. Lớp 3 — Auto-bid war loop (cốt lõi xử lý đồng thời)

Đây là phần tinh tế nhất. Khi nhiều bidder đăng ký auto-bid, hệ thống phải mô phỏng "cuộc chiến proxy bid" giống eBay: ai trả cao hơn sẽ thắng, mỗi người tự động lên giá theo `increment` cho tới khi 1 bên hết `maxBid`.

### Cấu trúc dữ liệu

```java
private final Map<Integer, PriorityQueue<AutoBidEntry>> autoBidQueues;

private static class AutoBidEntry {
    final Bidder bidder;
    final int bidderId;
    final BigDecimal maxBid;
    final BigDecimal increment;
    final LocalDateTime registeredAt;

    static final Comparator<AutoBidEntry> COMPARATOR = (a, b) -> {
        int cmp = b.maxBid.compareTo(a.maxBid);  // maxBid giảm dần
        if (cmp != 0) return cmp;
        return a.registeredAt.compareTo(b.registeredAt); // tie-break: đăng ký trước thắng
    };
}
```

`PriorityQueue` được sắp xếp theo:
1. `maxBid` giảm dần — người trả cao hơn được ưu tiên counter trước.
2. `registeredAt` tăng dần — cùng `maxBid`, ai đăng ký trước thắng.

### Vòng lặp đối đầu (`triggerAutoBids`)

Pseudocode:

```
function triggerAutoBids(auction, excludeBidderId):
    lastWinner = excludeBidderId
    repeat tới 100 lần:
        # Tìm auto-bid khả thi nhất NGOẠI TRỪ winner vòng trước
        best = first entry in queue where:
                 entry.bidderId != lastWinner
                 AND auction.currentPrice + entry.increment <= entry.maxBid

        if best == null:
            return   # không còn ai counter được → war kết thúc

        nextPrice = auction.currentPrice + best.increment
        update RAM + DB atomic (rollback nếu fail)
        broadcast "BID_UPDATE"
        check anti-snipe

        lastWinner = best.bidderId  # vòng sau loại winner ra
```

### Ví dụ trực tiếp (kịch bản 2 client cùng auto-bid)

Giả sử:
- Client 1 đăng ký TRƯỚC: `maxBid = 10,000,000`, `increment = 500,000`.
- Client 2 đăng ký SAU: `maxBid = 18,000,000`, `increment = 500,000`.
- Phiên bắt đầu ở giá khởi điểm `1,000,000`.

#### Bước 1 — Client 1 đăng ký

`registerAutoBid` thêm entry Client 1 vào queue. Queue: `[Client1(10M)]`.
Gọi `triggerAutoBids(excludeBidderId = currentWinnerId = 0)`. Currentprice 1M:
- Vòng 1: best = Client 1 (1M + 500k ≤ 10M ✓). Bid 1.5M. RAM/DB cập nhật. Broadcast. `lastWinner = Client 1`.
- Vòng 2: queue chỉ có Client 1 nhưng đã bị loại → best = null → return.

State: `currentPrice = 1.5M`, `winner = Client 1`.

#### Bước 2 — Client 2 đăng ký

`registerAutoBid` thêm entry Client 2 vào queue. Queue: `[Client2(18M), Client1(10M)]`.
Gọi `triggerAutoBids(excludeBidderId = Client 1)` (Client 1 đang là winner). Current price 1.5M:

| Vòng | Người counter | Giá mới | lastWinner sau vòng |
|---|---|---|---|
| 1 | Client 2 (1.5M + 500k = 2M ≤ 18M) | 2,000,000 | Client 2 |
| 2 | Client 1 (2M + 500k = 2.5M ≤ 10M) | 2,500,000 | Client 1 |
| 3 | Client 2 (2.5M + 500k = 3M ≤ 18M) | 3,000,000 | Client 2 |
| ... | ... | ... | ... |
| 17 | Client 1 (10M ≤ 10M ✓) | 10,000,000 | Client 1 |
| 18 | Client 2 (10M + 500k = 10.5M ≤ 18M) | 10,500,000 | Client 2 |
| 19 | Client 1: 10.5M + 500k = 11M > maxBid 10M ✗ | — | — |

→ best = null ở vòng 19 → return.

**Giá cuối: `10,500,000`. Winner: Client 2.** Đúng kỳ vọng — Client 2 chỉ phải trả vừa đủ để vượt Client 1, không lãng phí lên thẳng 18M.

### Tại sao vòng lặp PHẢI nằm trong cùng một lock?

Toàn bộ vòng lặp 18 nhịp đó chạy đồng bộ trong critical section của `placeBid` hoặc `registerAutoBid`. Nếu lock được nhả giữa các vòng:

- Một thread khác có thể chen vào và bid manual giữa cuộc war → log lệch.
- DB ghi dở dang → RAM/DB lệch.
- Push event các vòng có thể bị xen kẽ với push event khác → client thấy giá nhảy lung tung.

Vì lock per-auction nhả ra ngay sau khi war kết thúc, các phiên khác không bị ảnh hưởng. Cuộc war 18 vòng trên thực tế hoàn tất trong vài chục mili-giây (chỉ là cập nhật RAM + insert DB local).

### Vì sao có giới hạn `AUTO_BID_MAX_ROUNDS = 100`?

Phòng vệ: nếu có lỗi logic (ví dụ `increment = 0` mà filter validate sót), vòng lặp có thể vô hạn → giới hạn cứng đảm bảo lock không bị giữ mãi.

### `excludeBidderId` quan trọng thế nào?

`registerAutoBid` truyền `excludeBidderId = auction.getHighestBidderId()`. Nếu bidder vừa đăng ký mà đang dẫn đầu, vòng đầu tiên sẽ loại chính họ → không tự counter chính mình → không lãng phí tiền của user.

`placeBid` truyền `excludeBidderId = bidder.getId()` (người vừa bid manual). Nếu chính người đó cũng có auto-bid trong queue, vòng đầu loại họ → không tự counter mình ngay sau khi đã bid manual.

## 5. Đồng bộ phía Client (chart + history)

### Vấn đề "đồng hồ lệch"

2 client chạy ở 2 máy khác nhau có thể lệch giờ vài giây. Nếu chart dùng `LocalDateTime.now()` của client để gắn nhãn trục X, cùng một sự kiện `BID_UPDATE` sẽ hiện ở vị trí khác nhau trên 2 máy → nhìn như 2 chart khác nhau.

### Giải pháp 2 chiều

**(a) Server gắn `bidTime` vào event:**

```java
public static class BidEvent {
    public String bidTime;  // ISO-8601, server time
}

public static BidEvent bidUpdate(...) {
    e.bidTime = LocalDateTime.now().toString();  // tại SERVER
    return e;
}
```

**(b) Client offset đồng hồ:**

```java
// Khi GET_AUCTION_STATE trả về, payload có "serverNow"
LocalDateTime srvNow = LocalDateTime.parse(state.get("serverNow").getAsString());
clockOffset = Duration.between(LocalDateTime.now(), srvNow);

private LocalDateTime serverNow() {
    return LocalDateTime.now().plus(clockOffset);
}
```

Khi vẽ chart, ưu tiên `event.bidTime` từ server; nếu null → dùng `serverNow()` (đồng hồ local đã offset). Nhờ vậy 2 client lệch giờ vẫn vẽ điểm ở vị trí trục X giống hệt nhau.

### Avoid duplicate khi client mới vào giữa phiên

Client vào sau cần snapshot toàn bộ history. Cơ chế:

1. Client gửi `GET_AUCTION_STATE` → server trả về `currentPrice`, `winnerId`, `endTime`, `history[]`.
2. Trước khi flag `historyLoaded = true`, mọi push `BID_UPDATE` đến KHÔNG được vẽ vào chart — vì rebuild trong `applyAuctionState()` đã replay toàn bộ history (gồm bid mới đó).
3. Sau khi `applyAuctionState()` xong, `historyLoaded = true` → các push event sau này sẽ được append vào chart như bình thường.

Đây là pattern "snapshot-then-stream" giống Kafka consumer hoặc Postgres logical replication.

## 6. Anti-Sniping (chống bid giây cuối)

Mỗi bid (manual hoặc auto) sau khi thành công sẽ gọi:

```java
private void checkAntiSnipe(int auctionId, Auction auction) {
    long secondsLeft = ChronoUnit.SECONDS.between(now, auction.getEndTime());
    if (secondsLeft >= 0 && secondsLeft <= 30) {
        auction.setEndTime(auction.getEndTime().plusSeconds(60));
        BidEventBus.getInstance().broadcast(auctionId,
            BidEvent.auctionExtended(auctionId, newEnd.toString()));
    }
}
```

Bid trong 30 giây cuối → gia hạn thêm 60 giây. Cũng nằm trong critical section → không bị race với phiên kết thúc.

## 7. Tóm tắt — vì sao xử lý đồng thời an toàn

| Bài toán | Giải pháp |
|---|---|
| 2 client cùng PLACE_BID phiên #5 | Per-auction `ReentrantLock` — thread sau chờ thread trước hoàn tất |
| Phiên #1 đang bid không cản phiên #2 | Lock RIÊNG mỗi phiên (`Map<Integer, ReentrantLock>`) |
| RAM ≠ DB khi DB fail | Atomic: write DB trong lock + rollback RAM nếu fail |
| Lost update giữa update RAM và ghi DB | DB write nằm cùng lock với update RAM |
| Auto-bid war giữa N bidder | `triggerAutoBids` for-loop tối đa 100 vòng, mỗi vòng loại winner trước đó |
| Tự counter chính mình lúc đăng ký auto-bid | `excludeBidderId = currentWinnerId` thay vì `-1` |
| Vòng lặp vô hạn nếu logic sai | Giới hạn cứng `AUTO_BID_MAX_ROUNDS = 100` |
| Push event interleave với reply | `synchronized(out)` cho từng PrintWriter |
| Client mới thiếu lịch sử bid | `GET_AUCTION_STATE` snapshot + flag `historyLoaded` chặn duplicate |
| Đồng hồ 2 client lệch nhau | Server gửi `bidTime` + `serverNow`, client tính offset |
| Bid giây cuối làm phiên kết thúc | Anti-snipe gia hạn 60s, broadcast `AUCTION_EXTENDED` |

## 8. Test verification

Các unit test trong `AuctionManagerAutoBidTest.java` cover các khía cạnh chính:

- `autoBid_countersOpponentBid` — cuộc war 1 chiều
- `autoBid_doesNotExceedMaxBid` — không vượt maxBid
- `autoBid_higherMaxWins` — 2 auto-bid: max cao hơn thắng + giá kết thúc đúng ngưỡng
- `autoBid_sequentialRegister_runsFullWar` — kịch bản 2 client đăng ký TUẦN TỰ (chính kịch bản người dùng báo lỗi). Test verify war chạy đầy đủ qua nhiều vòng.
- `autoBid_registerWhileLeading_doesNotSelfCounter` — đang dẫn rồi đăng ký auto-bid không tự đẩy giá.
- `autoBid_doesNotCounterSelf` — bid manual rồi không tự auto counter mình.
- `autoBid_reRegisterReplaces` — đăng ký 2 lần thì entry mới ghi đè.

Chạy test:

```bash
cd /path/to/OAS7copy
mvn test -Dtest=AuctionManagerAutoBidTest
```

Kỳ vọng: `Tests run: 10, Failures: 0`.

## 9. Tham khảo nhanh code

| Thành phần | File | Vai trò |
|---|---|---|
| Per-auction lock | `AuctionManager.java` `auctionLocks` | Mutex |
| Atomic bid | `AuctionManager.placeBid` | RAM + DB cùng lock |
| Auto-bid war | `AuctionManager.triggerAutoBids` | For-loop max 100 vòng |
| Auto-bid registry | `AuctionManager.autoBidQueues` | PriorityQueue sắp theo maxBid desc |
| Snapshot thread-safe | `AuctionManager.snapshot` | Đọc consistent state dưới lock |
| Push event | `BidEventBus.broadcast` | Synchronized per-PrintWriter |
| Bid event payload | `BidEventBus.BidEvent` | Có `bidTime` từ server |
| Client offset đồng hồ | `ControllerBidding.serverNow` / `clockOffset` | Đồng bộ trục X chart |
| Snapshot-then-stream | `ControllerBidding.historyLoaded` | Tránh duplicate điểm chart |
