package com.auction.shared.model.notification;

/**
 * Phân loại thông báo trong hệ thống. Client dùng để chọn icon/màu sắc.
 */
public enum NotificationType {
    LOGIN_SUCCESS,        // Đăng nhập thành công
    ITEM_LISTED,          // (Seller) Sản phẩm X đã được đưa lên đấu giá
    BID_PLACED,           // (Bidder) Đã đặt giá thành công cho phiên X với giá Y
    BID_OUTBID,           // (Bidder) Mức đấu giá của bạn đã bị vượt
    AUTO_BID_MAX_REACHED, // (Bidder) Auto-bid đã đạt giá tối đa
    AUCTION_FINISHED,     // (All bidders) Phiên đấu giá đã kết thúc
    AUCTION_WON,          // (Winner) Bạn đã thắng phiên ...
    AUCTION_LOST,         // (Bidder thua) Phiên kết thúc, người thắng là Z
    AUCTION_RESULT_SELLER,// (Seller) Phiên của bạn kết thúc, người thắng là Z
    AUCTION_PAID,         // (Seller) Người thắng đã hoàn thiện thông tin
    AUCTION_CANCELED,     // (Winner+Seller) Phiên bị hủy do hoàn thiện thất bại
    AUCTION_RELISTED,     // (Cũ bidder) Sản phẩm X đã được mở đấu giá lại
    INFO_COMPLETION_FAILED // (Winner) Bạn không hoàn thiện thông tin trong 12h
}
