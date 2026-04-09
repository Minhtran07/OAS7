package com.auction.shared.model.auction;

import com.auction.shared.model.Entity;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;

import java.time.LocalDateTime;

public class Auction extends Entity {
    private Item item;
    private Bidder currentWinner;
    private double currentPrice;
    private LocalDateTime endTime;
    private String status; // "OPEN", "CLOSED"

    public Auction(Item item, LocalDateTime endTime) {
        this.item = item;
        this.endTime = endTime;
        this.currentPrice = item.getStartingPrice();
        this.status = "OPEN";
    }

    public boolean updateBid(Bidder bidder, double amount) {
        if (amount > this.currentPrice) {
            this.currentPrice = amount;
            this.currentWinner = bidder;
            return true;
        }
        return false;
    }
}
