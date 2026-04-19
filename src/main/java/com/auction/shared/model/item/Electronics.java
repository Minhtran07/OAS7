package com.auction.shared.model.item;

public class Electronics extends Item {
    private String brand;
    private String warrantyPeriod; // Thời gian bảo hành

    public Electronics(String name, String description, double startingPrice, double currentPrice, String brand, String warrantyPeriod) {
        super(name, description, startingPrice, currentPrice);
        this.brand = brand;
        this.warrantyPeriod = warrantyPeriod;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getWarrantyPeriod() {
        return warrantyPeriod;
    }

    public void setWarrantyMonths(int warrantyMonths) {
        this.warrantyPeriod = warrantyPeriod;
    }
}
