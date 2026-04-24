package com.auction.client.session;

import com.auction.shared.model.user.User;

/**
 * Singleton lưu thông tin user đang đăng nhập trong phiên làm việc.
 * Tất cả controller đều có thể lấy user hiện tại từ đây.
 */
public class UserSession {

    private static volatile UserSession instance;
    private User currentUser;

    private UserSession() {}

    public static UserSession getInstance() {
        if (instance == null) {
            synchronized (UserSession.class) {
                if (instance == null) {
                    instance = new UserSession();
                }
            }
        }
        return instance;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    public void logout() {
        this.currentUser = null;
    }

    public boolean isLoggedIn() {
        return currentUser != null;
    }
}
