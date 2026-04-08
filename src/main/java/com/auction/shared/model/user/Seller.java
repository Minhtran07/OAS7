package com.auction.shared.model.user;

public class Seller extends User {
    private String storeName;

    public Seller(String username, String password, String fullname, String email, String storeName) {
        super(username, password, fullname, email, "SELLER");
        this.storeName = storeName;
    }

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public String getStoreName() {
        return storeName;
    }
}
