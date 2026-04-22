package com.auction.shared.model.user;

import java.math.BigDecimal;

public class Bidder extends User {
    private double balance;

    public Bidder(String username, String password, String fullname, String email, BigDecimal balance) {
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
