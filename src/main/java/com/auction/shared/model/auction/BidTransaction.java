package com.auction.shared.model.auction;

import com.auction.shared.model.Entity;

import java.time.LocalDateTime;

public class BidTransaction {
    private int auctionId;
    private int bidderId;
    private double amount;
    private LocalDateTime timestamp;

    public BidTransaction(int bidderId, int auctionId, double amount) {
        this.bidderId = bidderId;
        this.auctionId = auctionId;
        this.amount = amount;
        this.timestamp = LocalDateTime.now();
    }
    public int getBidderId() { return bidderId; }
    public int getAuctionId() { return auctionId; }
    public double getAmount() { return amount; }
    public LocalDateTime getBidTime() { return timestamp; }
}
