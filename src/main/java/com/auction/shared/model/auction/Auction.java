package com.auction.shared.model.auction;

import com.auction.shared.model.Entity;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class Auction {  //Quá trình đấu giá
    private Item item;
    private Bidder currentWinner;
    private BigDecimal currentPrice;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Role status;
    private int highestBidderId;

    public Auction(Item item, LocalDateTime startTime, LocalDateTime endTime) {
        this.item = item;
        this.startTime = startTime;
        this.endTime = endTime;

        this.currentPrice = BigDecimal.valueOf(item.getStartingPrice());
        this.status = Role.OPEN;

    }

    public void updateStatusBasedOnTime() {
        LocalDateTime now = LocalDateTime.now();

        // Nếu đang OPEN và đã đến giờ bắt đầu -> Chuyển sang RUNNING
        if (status == Role.OPEN && (now.isEqual(startTime) || now.isAfter(startTime))) {
            this.status = Role.RUNNING;
        }

        // Nếu đang RUNNING và đã đến giờ kết thúc -> Chuyển sang FINISHED
        if (status == Role.RUNNING && (now.isEqual(endTime) || now.isAfter(endTime))) {
            this.status = Role.FINISHED;
        }
    }

    public boolean updateBid(Bidder bidder, BigDecimal amount) {
        if (amount.compareTo(this.currentPrice) > 0) {
            this.currentPrice = amount;
            this.currentWinner = bidder;
            this.highestBidderId = bidder.getId();
            return true;
        }
        return false;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public Role getStatus() {
        return status;
    }

    public void setStatus(Role status) {
        this.status = status;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public Bidder getCurrentWinner() {
        return currentWinner;
    }

    public void setCurrentWinner(Bidder currentWinner) {
        this.currentWinner = currentWinner;
    }

    public void setHighestBidderId(int highestBidderId) {
        this.highestBidderId = highestBidderId;
    }

    public int getHighestBidderId() {
        return highestBidderId;
    }
}
