package com.auction.shared.model.item;

import com.auction.shared.model.Entity;

public abstract class Item extends Entity {
    private String name;
    private String description; // Mô tả
    private double startingPrice;
    private double currentPrice;  // Giá hiện tại (sau khi bid)

    public Item(String name, String description, double startingPrice, double currentPrice) {
        this.name = name;
        this.description = description;
        this.startingPrice = startingPrice;
        this.currentPrice = currentPrice;
    }

    public String getName() {
        return name;
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

    public void setName(String name) {
        this.name = name;
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
}
