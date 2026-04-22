package com.auction.shared.model.user;

public class Admin extends User {
    public Admin(String username, String password, String fullname, String email) {
        super(id, username, password, fullname, email, "ADMIN" );
    }
}
