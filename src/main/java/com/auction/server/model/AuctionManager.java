package com.auction.server.model;

import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.user.Bidder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class AuctionManager {
    private static final Logger logger = LoggerFactory.getLogger(AuctionManager.class);

    private static volatile AuctionManager instance;

    private final Map<Integer, Auction> activeAuctions;

    private final ReentrantLock bidLock;

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

    public boolean placeBid(int auctionId, Bidder bidder, double bidAmount) {
        bidLock.lock();
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

            return auction.updateBid(bidder, bidAmount);

        } finally {
            bidLock.unlock();
        }
    }
}