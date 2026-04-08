package com.auction.shared.model.user;

import com.auction.shared.model.auction.Auction;

public class Bidder extends User {
    private double balance;

    public Bidder(String username, String password, String fullname, String email, double balance) {
        super(username, password, fullname, email, "BIDDER");
        this.balance = balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public double getBalance() {
        return balance;
    }
}
