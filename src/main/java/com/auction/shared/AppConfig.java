package com.auction.shared;

/**
 * Hằng số/cấu hình toàn hệ thống. Có thể override qua system property
 * (ví dụ: -Dauction.completionTimeoutMinutes=1) để dễ demo / test.
 */
public final class AppConfig {

    private AppConfig() {}

    /**
     * Thời gian (phút) người thắng được phép "hoàn thiện thông tin"
     * trước khi phiên bị tự động chuyển sang CANCELED.
     * Mặc định: 720 phút = 12 giờ (theo yêu cầu).
     * Override qua: -Dauction.completionTimeoutMinutes=N
     */
    public static final long COMPLETION_TIMEOUT_MINUTES =
            readLong("auction.completionTimeoutMinutes", 720L);

    /**
     * Chu kỳ quét timeout completion (giây).
     * Override qua: -Dauction.completionScanIntervalSec=N
     */
    public static final long COMPLETION_SCAN_INTERVAL_SEC =
            readLong("auction.completionScanIntervalSec", 30L);

    private static long readLong(String key, long defaultValue) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
