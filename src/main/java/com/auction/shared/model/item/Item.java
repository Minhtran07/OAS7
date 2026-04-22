package com.auction.shared.model.item;

import com.auction.shared.model.Entity;
import com.auction.shared.model.user.User;

public abstract class Item extends Entity {
    private String category;
    private String name;
    private int sellerID;
    private String description;
    private double startingPrice;
    private double currentPrice;
    private User seller;

    public Item(int id,String category, String name, int sellerID, String description, double startingPrice, double currentPrice) {
        super(id);
        this.category = category;
        this.name = name;
        this.sellerID = sellerID;
        this.description = description;
        this.startingPrice = startingPrice;
        this.currentPrice = currentPrice;
    }

    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public int getSellerID() {
        return sellerID;
    }

    public String getDescription() {
        return description;
    }

    public double getStartingPrice() {
        return startingPrice;
    }

    public double getCurrentPrice() {
        return currentPrice;
    }

    public User getSeller() {
        return seller;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setSellerID(int sellerID) {
        this.sellerID = sellerID;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setStartingPrice(double startingPrice) {
        this.startingPrice = startingPrice;
    }

    public void setCurrentPrice(double currentPrice) {
        this.currentPrice = currentPrice;
    }

    public void setSeller(User seller) {
        this.seller = seller;
    }
}