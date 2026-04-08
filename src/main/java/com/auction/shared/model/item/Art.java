package com.auction.shared.model.item;

public class Art extends Item {
    private String artist;
    private String material;

    public Art(String name, String description, double startingPrice, double currentPrice, String artist, String material) {
        super(name, description, startingPrice, currentPrice);
        this.artist = artist;
        this.material = material;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getMaterial() {
        return material;
    }

    public void setMaterial(String material) {
        this.material = material;
    }
}
