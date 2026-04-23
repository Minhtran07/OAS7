package com.auction.shared.model.item;

public class Electronics extends Item {
    private String brand;
    private int warrantyPeriod; // Thời gian bảo hành


    public Electronics(int id, String category, String name, int sellerID, String description, double startingPrice, double currentPrice, String brand, int warrantyPeriod) {
        super(id, category, name, sellerID, description, startingPrice, currentPrice);
        this.setCategory("ELECTRONICS");
        this.brand = brand;
        this.warrantyPeriod = warrantyPeriod;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public int getWarrantyPeriod() {
        return warrantyPeriod;
    }

    public void setWarrantyMonths(int warrantyMonths) {
        this.warrantyPeriod = warrantyPeriod;
    }
}