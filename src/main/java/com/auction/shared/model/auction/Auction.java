package com.auction.shared.model.auction;

import com.auction.shared.model.Entity;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;

import java.time.LocalDateTime;

public class Auction extends Entity {
    private Item item;
    private Bidder currentWinner;
    private double currentPrice;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private int highestBidderId;

    public Auction(Item item, LocalDateTime startTime, LocalDateTime endTime) {
        this.item = item;
        this.startTime = startTime;
        this.endTime = endTime;
        this.currentPrice = item.getStartingPrice();
        this.status = "OPEN";
    }

    public void updateStatusBasedOnTime() {
        LocalDateTime now = LocalDateTime.now();

        // Nếu đang OPEN và đã đến giờ bắt đầu -> Chuyển sang RUNNING
        if (status.equals("OPEN") && (now.isEqual(startTime) || now.isAfter(startTime))) {
            this.status = "RUNNING";
        }

        // Nếu đang RUNNING và đã đến giờ kết thúc -> Chuyển sang FINISHED
        if (status.equals("RUNNING") && (now.isEqual(endTime) || now.isAfter(endTime))) {
            this.status = "FINISHED";
        }
    }

    public boolean updateBid(Bidder bidder, double amount) {
        if (amount > this.currentPrice) {
            this.currentPrice = amount;
            this.currentWinner = bidder;
            this.highestBidderId = bidder.getId();
            return true;
        }
        return false;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(double currentPrice) {
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
