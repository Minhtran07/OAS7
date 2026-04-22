package com.auction.shared.model;

import java.io.Serializable;

public abstract class Entity implements Serializable {
    protected int id;

    public Entity(int id) {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }
}