package com.auction.server.model;
// đồng bộ đa luồng, đấu giá hợp lệ,...
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.user.Bidder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class AuctionManager {
    private static final Logger logger = LoggerFactory.getLogger(AuctionManager.class);
    // dùng logger của slf4j để in ra các thông báo thay vì s.o.p
    private static volatile AuctionManager instance;
    // volatile ép luồng đọc/ghi dữ liệu trực tiếp và RAM chính thay vì bộ nhớ đệm riêng
    private final Map<Integer, Auction> activeAuctions;

    private final ReentrantLock bidLock;
    // map chứa các phiên đang chạy, reentrantlock tránh xung đột dữ liệu, đảm bảo tại 1 thời điểm chỉ có 1 thread thao tác vùng dữ liệu
    private AuctionManager() {
        activeAuctions = new ConcurrentHashMap<>();
        bidLock = new ReentrantLock();
    }

    public static AuctionManager getInstance() {
        if (instance == null) {
            synchronized (AuctionManager.class) {
                if (instance == null) {
                    instance = new AuctionManager();
                }
            }
        }
        return instance;
    }
    // đảm bảo toàn server chỉ có 1 đối tượng AuctionManager được tạo ra ( singleton + double-checked looking)
    // khi có luồng chạy đến, nếu chưa có instance (quản lý), khóa lại và tạo mới
    // với luồng đến sau mà gần như cùng lúc, sau khi bị khóa ở synchronized (AuctionManager.class) {, chờ luồng trước tạo quản lý xong và ra, sẽ phải kiểm tra thêm 1 lần nữa xem đã có quản lý được tạo chưa (instance != null), nếu có rồi thì phải quay lại
    public boolean placeBid(int auctionId, Bidder bidder, BigDecimal bidAmount) {
        bidLock.lock();
        // reentrantlock, khóa lại ngay khi có người đầu tiên đến, những người sau phải đợi
        try {
            Auction auction = activeAuctions.get(auctionId);
            if (auction == null) {
                logger.warn("Phiên đấu giá {} không tồn tại.", auctionId);
                return false;
            }

            auction.updateStatusBasedOnTime();

            if (!auction.getStatus().equals("RUNNING")) {
                logger.warn("Từ chối đặt giá. Trạng thái hiện tại: {}", auction.getStatus());
                return false;
            }
            // cập nhật thời gian  trước khi xét duyệt, nếu trạng thái đáu giá không phải running thì không chho placebid
            return auction.updateBid(bidder, bidAmount);

        } finally {
            bidLock.unlock();
            // mở khóa và nhường chỗ cho luồng tiếp theo
        }
    }
}