package com.auction.shared.model.item;

public class Vehicle extends Item {
    private int year;

    public Vehicle(String name, String description, double startingPrice, double currentPrice, int year) {
        super(name, description, startingPrice, currentPrice);
        this.year = year;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }
}
