package com.auction.shared.model.notification;

/**
 * DTO biểu diễn 1 thông báo gửi giữa server và client.
 * Dùng cho cả push (PUSH:NOTIFICATION:...) lẫn list (GET_NOTIFICATIONS).
 */
public class Notification {
    public int    id;
    public int    userId;
    public String type;              // NotificationType.name()
    public String title;
    public String message;
    public Integer relatedAuctionId; // có thể null
    public Integer relatedItemId;    // có thể null
    public boolean isRead;
    public String createdAt;         // ISO local datetime

    public Notification() {}

    public Notification(int userId, NotificationType type, String title, String message,
                        Integer relatedAuctionId, Integer relatedItemId) {
        this.userId = userId;
        this.type = type.name();
        this.title = title;
        this.message = message;
        this.relatedAuctionId = relatedAuctionId;
        this.relatedItemId = relatedItemId;
    }
}
