package com.auction.shared.model.auction;

import com.auction.shared.model.Entity;

import java.time.LocalDateTime;

public class BidTransaction extends Entity {
    private String auctionId;
    private String bidderId;
    private double amount;
    private LocalDateTime timestamp;

    public BidTransaction(String bidderId, String auctionId, double amount) {
        this.bidderId = bidderId;
        this.auctionId = auctionId;
        this.amount = amount;
        this.timestamp = LocalDateTime.now();
    }
    public String getBidderId() { return bidderId; }
    public String getAuctionId() { return auctionId; }
    public double getAmount() { return amount; }
    public LocalDateTime getBidTime() { return timestamp; }
}
