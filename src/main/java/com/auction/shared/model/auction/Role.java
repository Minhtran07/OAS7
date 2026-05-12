package com.auction.shared.model.auction;


/**
 * Trạng thái vòng đời của một phiên đấu giá.
 *
 *   OPEN      — Đã tạo, chờ đến giờ bắt đầu
 *   RUNNING   — Đang diễn ra, bidder có thể đặt giá
 *   FINISHED  — Đã kết thúc, đang chờ người thắng hoàn thiện thông tin (≤12h)
 *   PAID      — Người thắng đã hoàn thiện thông tin trong hạn → giao dịch hoàn tất
 *   CANCELED  — Phiên bị hủy (do người thắng không hoàn thiện trong 12h
 *               hoặc do seller chủ động hủy)
 *   CLOSED    — (legacy) — giữ để tương thích dữ liệu cũ, ngữ nghĩa tương đương FINISHED.
 */
public enum Role {
    OPEN, RUNNING, FINISHED, PAID, CANCELED, CLOSED;

    /** Status được coi là "đã kết thúc đấu giá nhưng chưa thanh toán" */
    public boolean isFinishedAwaitingPayment() {
        return this == FINISHED;
    }

    /** Status được coi là phiên đã đóng (không nhận bid nữa) */
    public boolean isTerminal() {
        return this == FINISHED || this == PAID || this == CANCELED || this == CLOSED;
    }
}
