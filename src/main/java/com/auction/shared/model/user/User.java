package com.auction.shared.model.user;

import com.auction.shared.model.Entity;

public abstract class User extends Entity {
    private String username;
    private String password;
    private String fullname;
    private String email;
    private Role role; // "BIDDER", "SELLER", "ADMIN"


    public User(int id, String username, String password, String fullname, String email, Role role) {
        super(id);
        this.username = username;
        this.password = password;
        this.fullname = fullname;
        this.email = email;
        this.role = role;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Role getRole() {
        return role;
    }

    public String getFullname() {
        return fullname;
    }
}
