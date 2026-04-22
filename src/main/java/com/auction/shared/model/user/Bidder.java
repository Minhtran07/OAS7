package com.auction.shared.model.user;

import java.math.BigDecimal;

public class Bidder extends User {
    private BigDecimal balance;

    public Bidder(int id ,String username, String password, String fullname, String email,BigDecimal balance) {
        super(id, username, password, fullname, email, Role.BIDDER);
        this.balance = balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    } // Balance: Số dư

    public BigDecimal getBalance() {
        return balance;
    }
}
