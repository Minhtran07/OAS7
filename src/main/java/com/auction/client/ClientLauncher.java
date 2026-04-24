package com.auction.client;

/**
 * Launcher class để bypass JavaFX module-path check.
 *
 * Lý do tồn tại: từ JDK 11+, JavaFX không còn đi kèm JDK. Khi main class
 * extends javafx.application.Application, JVM kiểm tra module javafx.graphics
 * trong module-path ngay ở startup — nếu JavaFX chỉ có trong classpath
 * (ví dụ chạy qua Maven dependency bình thường), JVM sẽ ném lỗi:
 *     "Error: JavaFX runtime components are missing"
 *
 * Workaround: main class KHÔNG extend Application — chỉ đơn giản gọi
 * MainClient.main(...) ở runtime, lúc đó classloader đã nạp JavaFX jar
 * từ classpath.
 *
 * Cách chạy:
 *   java -cp <classpath> com.auction.client.ClientLauncher
 */
public class ClientLauncher {
    public static void main(String[] args) {
        MainClient.main(args);
    }
}
