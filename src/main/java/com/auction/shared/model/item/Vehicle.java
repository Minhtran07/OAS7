package com.auction.shared.model.item;

public class Vehicle extends Item {
    private int modelYear;

    public Vehicle(int id, String category, String name, int sellerID, String description, double startingPrice, double currentPrice, int modelYear) {
        super(id, category, name, sellerID, description, startingPrice, currentPrice);
        this.setCategory("VEHICLE");
        this.modelYear = modelYear;
    }

    public int getYear() {
        return modelYear;
    }

    public void setYear(int year) {
        this.modelYear = year;
    }
}