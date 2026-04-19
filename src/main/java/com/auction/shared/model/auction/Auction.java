package com.auction.shared.model.auction;

import com.auction.shared.model.Entity;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;

import java.time.LocalDateTime;

public class Auction {  //Quá trình đấu giá
    private Item item;
    private Bidder currentWinner;  // Người đặt giá max
    private double currentPrice;    //Giá hiện tại( Giá max )
    private LocalDateTime endTime;
    private Role status; // "OPEN", "CLOSED"

    public Auction(Item item, LocalDateTime endTime) {
        this.item = item;
        this.endTime = endTime;
        this.currentPrice = item.getStartingPrice();
        this.status = Role.OPEN;
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
