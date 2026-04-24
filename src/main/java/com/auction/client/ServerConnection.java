package com.auction.client;

import com.auction.shared.network.Request;
import com.auction.shared.network.Response;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Singleton quản lý kết nối TCP từ client đến server.
 *
 * Kiến trúc:
 *  - Một LISTENER THREAD duy nhất đọc stream → phân loại:
 *      • Dòng bắt đầu "PUSH:" → forward cho PushListener (callback UI)
 *      • Dòng khác              → push vào responseQueue
 *  - sendRequest() ghi request rồi poll response từ queue (timeout 10s).
 *
 * Nhờ vậy KHÔNG còn race-condition giữa listener và sendRequest như
 * phiên bản pause/resume cũ.
 */
public class ServerConnection {

    private static final String HOST            = "localhost";
    private static final int    PORT            = 3667;
    private static final int    CONNECT_TIMEOUT = 5_000;   // 5s timeout kết nối
    private static final int    REPLY_TIMEOUT_S = 10;      // 10s chờ reply
    private static final int    MAX_RETRIES     = 3;
    private static final long   RETRY_DELAY_MS  = 1_000;   // 1s giữa các lần thử

    private static volatile ServerConnection instance;

    private Socket         socket;
    private PrintWriter    out;
    private BufferedReader in;
    private final Gson     gson = new Gson();

    // Hàng đợi cho các reply từ server (mỗi sendRequest lấy 1)
    private final LinkedBlockingQueue<String> responseQueue = new LinkedBlockingQueue<>();

    // Listener nhận push event
    private PushListener    pushListener;
    private Thread          listenerThread;
    private volatile boolean running = false;

    private ServerConnection() {}

    public static ServerConnection getInstance() {
        if (instance == null) {
            synchronized (ServerConnection.class) {
                if (instance == null) {
                    instance = new ServerConnection();
                }
            }
        }
        return instance;
    }

    // ─── Kết nối ─────────────────────────────────────────────────────────────

    public void connect() throws IOException {
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                socket = new Socket();
                socket.connect(new java.net.InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
                out = new PrintWriter(socket.getOutputStream(), true);
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                responseQueue.clear();
                startListenerThread();
                return;
            } catch (ConnectException e) {
                lastError = e;
                System.err.printf("[ServerConnection] Lần thử %d/%d thất bại: %s%n",
                        attempt, MAX_RETRIES, e.getMessage());
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Kết nối bị ngắt", ie);
                    }
                }
            } catch (SocketTimeoutException e) {
                lastError = new IOException("Hết thời gian chờ kết nối (>5s)", e);
                break;
            }
        }

        throw new IOException(
                "Không thể kết nối đến máy chủ " + HOST + ":" + PORT
                + " sau " + MAX_RETRIES + " lần thử. "
                + (lastError != null ? lastError.getMessage() : ""),
                lastError
        );
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    // ─── Gửi Request (đồng bộ) ───────────────────────────────────────────────

    public synchronized Response sendRequest(String action, String payload) {
        try {
            if (!isConnected()) connect();

            // Dọn các reply rác trước khi gửi (phòng trường hợp timeout cũ còn sót)
            responseQueue.clear();

            Request request = new Request(action, payload);
            out.println(gson.toJson(request));

            String jsonResponse = responseQueue.poll(REPLY_TIMEOUT_S, TimeUnit.SECONDS);
            if (jsonResponse == null) {
                return new Response("ERROR",
                        "Hết thời gian chờ phản hồi từ server (>" + REPLY_TIMEOUT_S + "s)", null);
            }
            return gson.fromJson(jsonResponse, Response.class);

        } catch (IOException e) {
            String friendlyMsg = buildFriendlyError(e);
            System.err.println("[ServerConnection] " + friendlyMsg);
            return new Response("ERROR", friendlyMsg, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Response("ERROR", "Thao tác bị hủy", null);
        } catch (Exception e) {
            System.err.println("[ServerConnection] Lỗi xử lý response: " + e.getMessage());
            return new Response("ERROR", "Lỗi xử lý phản hồi: " + e.getMessage(), null);
        }
    }

    private String buildFriendlyError(IOException e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("Connection refused") || msg.contains("kết nối đến máy chủ")) {
            return "Máy chủ chưa khởi động hoặc không thể kết nối (cổng " + PORT + ")";
        }
        if (msg.contains("timed out") || msg.contains("thời gian")) {
            return "Hết thời gian chờ phản hồi từ máy chủ";
        }
        return "Lỗi mạng: " + msg;
    }

    // ─── Listener Thread (người đọc duy nhất của stream) ─────────────────────

    public interface PushListener {
        void onPushEvent(String eventJson);
    }

    public void setPushListener(PushListener listener) {
        this.pushListener = listener;
    }

    public void clearPushListener() {
        this.pushListener = null;
    }

    private void startListenerThread() {
        running = true;
        listenerThread = new Thread(() -> {
            try {
                String line;
                while (running && (line = in.readLine()) != null) {
                    if (line.startsWith("PUSH:")) {
                        String eventJson = line.substring(5);
                        if (pushListener != null) {
                            javafx.application.Platform.runLater(
                                () -> pushListener.onPushEvent(eventJson)
                            );
                        }
                    } else {
                        // Đây là reply cho 1 sendRequest đang chờ
                        responseQueue.offer(line);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("[ServerConnection] Listener lỗi: " + e.getMessage());
                }
            }
        }, "server-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    // ─── Disconnect ──────────────────────────────────────────────────────────

    public void disconnect() {
        running = false;
        if (listenerThread != null) listenerThread.interrupt();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
    }
}
