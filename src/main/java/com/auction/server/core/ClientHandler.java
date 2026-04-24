package com.auction.server.core;
//luồng riêng cho 1 client để yêu cầu và nhận phản hồi
import com.auction.server.dao.AuctionDAO;
import com.auction.server.dao.ItemDAO;
import com.auction.server.dao.UserDAO;
import com.auction.server.model.AuctionManager;
import com.auction.shared.model.auction.Auction;
import com.auction.shared.model.item.Item;
import com.auction.shared.model.user.Bidder;
import com.auction.shared.model.user.Seller;
import com.auction.shared.model.user.User;
import com.auction.shared.network.Request;
import com.auction.shared.network.Response;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

public class ClientHandler implements Runnable { // implements Runnable để biến class thành thread
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    private final Socket socket; // ống mạng
    private final Gson gson; // dịch ngôn ngữ
    private PrintWriter out; // gửi data
    private BufferedReader in; // nhận data

    private final UserDAO    userDAO;
    private final AuctionDAO auctionDAO;
    private final ItemDAO    itemDAO;

    // Theo dõi các auction mà client này đang subscribe (để unsubscribe khi disconnect)
    private final Set<Integer> subscribedAuctions = new HashSet<>();

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.gson = new Gson();
        this.userDAO = new UserDAO();
        this.auctionDAO = new AuctionDAO();
        this.itemDAO = new ItemDAO();
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            // mở khóa luồng dữ liệu vào và ra của socket
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                logger.info("Nhận được gói tin thô: {}", inputLine);

                Request request = gson.fromJson(inputLine, Request.class);

                Response response;
                switch (request.getAction()) {
                    case "LOGIN":
                        response = handleLogin(request.getPayload());
                        break;
                    case "REGISTER":
                        response = handleRegister(request.getPayload());
                        break;
                    case "PLACE_BID":
                        response = handlePlaceBid(request.getPayload());
                        break;
                    case "GET_AUCTIONS":
                        response = handleGetAuctions();
                        break;
                    case "CREATE_AUCTION":
                        response = handleCreateAuction(request.getPayload());
                        break;
                    case "SUBSCRIBE_AUCTION":
                        response = handleSubscribeAuction(request.getPayload());
                        break;
                    case "UNSUBSCRIBE_AUCTION":
                        response = handleUnsubscribeAuction(request.getPayload());
                        break;
                    case "GET_AUCTION_STATE":
                        response = handleGetAuctionState(request.getPayload());
                        break;
                    case "AUTO_BID":
                        response = handleAutoBid(request.getPayload());
                        break;
                    case "GET_MY_ITEMS":
                        response = handleGetMyItems(request.getPayload());
                        break;
                    case "ADD_ITEM":
                        response = handleAddItem(request.getPayload());
                        break;
                    case "UPDATE_ITEM":
                        response = handleUpdateItem(request.getPayload());
                        break;
                    case "DELETE_ITEM":
                        response = handleDeleteItem(request.getPayload());
                        break;
                    default:
                        response = new Response("ERROR", "Hệ thống không hiểu lệnh: " + request.getAction(), null);
                        break;
                }

                String jsonResponse = gson.toJson(response);
                out.println(jsonResponse);
            }
            // nhận chuỗi json -> biến thành đối tượng request -> xử lý -> tạo đối tượng response -> biến thành chuỗi json -> gửi đi
        } catch (IOException e) {
            logger.warn("Mất kết nối với Client: {}", socket.getInetAddress().getHostAddress());
            // client thoát hoặc văng
        } finally {
            closeConnections();
        }
    }

    private Response handleLogin(String payload) {
        try {
            JsonObject credentials = gson.fromJson(payload, JsonObject.class);
            String username = credentials.get("username").getAsString();
            String password = credentials.get("password").getAsString();

            User loggedInUser = userDAO.login(username, password);

            if (loggedInUser != null) {
                String userJson = gson.toJson(loggedInUser);
                return new Response("SUCCESS", "Đăng nhập thành công!", userJson);
            } else {
                return new Response("FAIL", "Sai tài khoản hoặc mật khẩu!", null);
            }
        } catch (Exception e) {
            logger.error("Lỗi khi xử lý Đăng nhập", e);
            return new Response("ERROR", "Dữ liệu gửi lên không đúng định dạng", null);
        }
    }

    private Response handleRegister(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);

            String username = data.get("username").getAsString();
            String password = data.get("password").getAsString();
            String fullname = data.get("fullname").getAsString();
            String email = data.get("email").getAsString();
            String role = data.get("role").getAsString().toUpperCase();

            User newUser = null;

            if (role.equals("BIDDER")) {
                // Nếu là Bidder, mặc định tạo tài khoản có 0 đồng (hoặc lấy từ JSON nếu có)
                BigDecimal initialBalance = new BigDecimal("0");
                newUser = new Bidder(0, username, password, fullname, email, initialBalance);

            } else if (role.equals("SELLER")) {
                String storeName = data.has("storeName") ? data.get("storeName").getAsString() : "Cửa hàng của " + fullname;
                newUser = new Seller(0, username, password, fullname, email, storeName);

            } else {
                return new Response("FAIL", "Lỗi: Role đăng ký không hợp lệ! Chỉ nhận BIDDER hoặc SELLER.", null);
            }

            boolean isSuccess = userDAO.register(newUser);

            if (isSuccess) {
                return new Response("SUCCESS", "Đăng ký tài khoản thành công!", null);
            } else {
                return new Response("FAIL", "Đăng ký thất bại! Có thể Username đã tồn tại.", null);
            }

        } catch (Exception e) {
            logger.error("Lỗi khi xử lý Đăng ký", e);
            return new Response("ERROR", "Dữ liệu JSON gửi lên bị thiếu trường hoặc sai định dạng", null);
        }
    }

    private Response handlePlaceBid(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);

            int auctionId = data.get("auctionId").getAsInt();
            int bidderId  = data.get("bidderId").getAsInt();
            double amount = data.get("amount").getAsDouble();

            // Lấy thông tin người dùng từ DB
            User user = userDAO.findById(bidderId);
            if (user == null) {
                return new Response("FAIL", "Người dùng không tồn tại", null);
            }
            if (!(user instanceof Bidder)) {
                logger.warn("Seller/Admin {} thử đặt giá phiên #{}", user.getUsername(), auctionId);
                return new Response("FAIL",
                        "Chỉ Bidder mới được đặt giá. Tài khoản Seller không thể tham gia đấu giá.", null);
            }
            Bidder bidder = (Bidder) user;

            // Gọi AuctionManager — thread-safe, có lock bên trong
            boolean success = AuctionManager.getInstance()
                    .placeBid(auctionId, bidder, BigDecimal.valueOf(amount));

            if (success) {
                // Ghi bid vào DB (lịch sử) và cập nhật giá hiện tại
                auctionDAO.recordBid(auctionId, bidderId, amount);
                auctionDAO.updateCurrentPrice(auctionId, amount, bidderId);

                logger.info("Bid thành công: auctionId={}, bidderId={}, amount={}", auctionId, bidderId, amount);

                JsonObject responseData = new JsonObject();
                responseData.addProperty("auctionId", auctionId);
                responseData.addProperty("newPrice", amount);
                responseData.addProperty("winnerId", bidderId);
                return new Response("SUCCESS", "Đặt giá thành công!", responseData.toString());
            } else {
                return new Response("FAIL", "Đặt giá thất bại! Giá phải cao hơn giá hiện tại hoặc phiên đã kết thúc.", null);
            }

        } catch (Exception e) {
            logger.error("Lỗi khi xử lý PLACE_BID", e);
            return new Response("ERROR", "Dữ liệu gửi lên không đúng định dạng", null);
        }
    }

    private Response handleGetAuctions() {
        try {
            java.util.List<AuctionDAO.AuctionRow> auctions = auctionDAO.getAllAuctions();
            logger.info("GET_AUCTIONS: trả về {} phiên đấu giá", auctions.size());
            String json = gson.toJson(auctions);
            return new Response("SUCCESS", "OK", json);
        } catch (Exception e) {
            logger.error("Lỗi GET_AUCTIONS: {}", e.getMessage(), e);
            return new Response("ERROR", "Không thể lấy danh sách phiên đấu giá: " + e.getMessage(), null);
        }
    }

    private Response handleCreateAuction(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int itemId           = data.get("itemId").getAsInt();
            double startingPrice = data.get("startingPrice").getAsDouble();
            String startTimeStr  = data.get("startTime").getAsString();
            String endTimeStr    = data.get("endTime").getAsString();

            // CHẶN TẠO TRÙNG: mỗi item chỉ có 1 phiên OPEN/RUNNING tại một thời điểm
            if (auctionDAO.hasActiveAuctionForItem(itemId)) {
                logger.warn("Seller cố tạo phiên trùng cho item {} (đã có phiên active)", itemId);
                return new Response("FAIL",
                        "Sản phẩm này đang có phiên đấu giá đang diễn ra. " +
                        "Chờ phiên kết thúc trước khi tạo phiên mới.", null);
            }

            java.time.LocalDateTime startTime = java.time.LocalDateTime.parse(startTimeStr);
            java.time.LocalDateTime endTime   = java.time.LocalDateTime.parse(endTimeStr);

            int auctionId = auctionDAO.createAuction(itemId, startingPrice, startTime, endTime);

            if (auctionId > 0) {
                // Đánh dấu item đang trong phiên đấu giá để không cho tạo phiên khác
                itemDAO.updateStatus(itemId, "IN_AUCTION");

                // Thêm vào AuctionManager để quản lý realtime ngay lập tức
                Item item = itemDAO.findById(itemId);
                if (item != null) {
                    Auction auction = new Auction(item, startTime, endTime);
                    auction.setCurrentPrice(java.math.BigDecimal.valueOf(startingPrice));
                    AuctionManager.getInstance().addAuction(auctionId, auction);
                    logger.info("Đã thêm phiên #{} vào AuctionManager", auctionId);
                }
                JsonObject result = new JsonObject();
                result.addProperty("auctionId", auctionId);
                return new Response("SUCCESS", "Tạo phiên đấu giá thành công!", result.toString());
            } else {
                return new Response("FAIL", "Không thể tạo phiên đấu giá", null);
            }

        } catch (Exception e) {
            logger.error("Lỗi CREATE_AUCTION", e);
            return new Response("ERROR", "Dữ liệu không hợp lệ", null);
        }
    }

    private Response handleSubscribeAuction(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int auctionId = data.get("auctionId").getAsInt();
            BidEventBus.getInstance().subscribe(auctionId, out);
            subscribedAuctions.add(auctionId);
            return new Response("SUCCESS", "Đã subscribe phiên #" + auctionId, null);
        } catch (Exception e) {
            logger.error("Lỗi SUBSCRIBE_AUCTION", e);
            return new Response("ERROR", "Không thể subscribe", null);
        }
    }

    private Response handleUnsubscribeAuction(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int auctionId = data.get("auctionId").getAsInt();
            BidEventBus.getInstance().unsubscribe(auctionId, out);
            subscribedAuctions.remove(auctionId);
            return new Response("SUCCESS", "Đã unsubscribe phiên #" + auctionId, null);
        } catch (Exception e) {
            return new Response("ERROR", "Không thể unsubscribe", null);
        }
    }

    /**
     * Snapshot hiện tại của 1 phiên đấu giá — dùng khi client mới mở
     * màn hình bidding để có đủ: giá hiện tại, winner, endTime (có thể đã
     * được anti-sniping gia hạn), toàn bộ lịch sử bid để vẽ chart + history.
     *
     * Những thông tin này KHÔNG đến qua push event (push chỉ gửi khi có bid
     * MỚI), nên client nào vào sau sẽ thiếu — đây chính là nguyên nhân
     * seller thấy "Chưa có người đặt giá" dù đã có bid trước đó.
     */
    private Response handleGetAuctionState(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int auctionId = data.get("auctionId").getAsInt();

            AuctionDAO.AuctionRow auction = auctionDAO.getAuctionById(auctionId);
            if (auction == null) {
                return new Response("FAIL", "Phiên đấu giá không tồn tại", null);
            }

            // Nếu AuctionManager đang giữ bản mới hơn (đã bị anti-sniping gia hạn)
            // thì dùng endTime / currentPrice từ RAM để tránh stale.
            Auction inMemory = AuctionManager.getInstance().getAuction(auctionId);
            if (inMemory != null) {
                if (inMemory.getEndTime() != null) {
                    auction.endTime = inMemory.getEndTime().toString();
                }
                if (inMemory.getCurrentPrice() != null) {
                    auction.currentPrice = inMemory.getCurrentPrice().doubleValue();
                }
            }

            java.util.List<AuctionDAO.BidRow> history = auctionDAO.getBidHistory(auctionId);

            JsonObject result = new JsonObject();
            result.addProperty("auctionId",    auction.id);
            result.addProperty("currentPrice", auction.currentPrice);
            result.addProperty("endTime",      auction.endTime);
            result.addProperty("winnerId",     auction.winnerId);
            if (auction.winnerName != null) {
                result.addProperty("winnerName", auction.winnerName);
            }
            result.add("history", gson.toJsonTree(history));

            return new Response("SUCCESS", "OK", result.toString());

        } catch (Exception e) {
            logger.error("Lỗi GET_AUCTION_STATE", e);
            return new Response("ERROR", "Không thể lấy trạng thái phiên", null);
        }
    }

    private Response handleAutoBid(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int auctionId  = data.get("auctionId").getAsInt();
            int bidderId   = data.get("bidderId").getAsInt();
            double maxBid  = data.get("maxBid").getAsDouble();
            double increment = data.get("increment").getAsDouble();

            User user = userDAO.findById(bidderId);
            if (user == null) {
                return new Response("FAIL", "Người dùng không tồn tại", null);
            }
            if (!(user instanceof Bidder)) {
                logger.warn("Seller/Admin {} thử auto-bid phiên #{}", user.getUsername(), auctionId);
                return new Response("FAIL",
                        "Chỉ Bidder mới được dùng auto-bid. Tài khoản Seller không thể tham gia đấu giá.", null);
            }
            Bidder bidder = (Bidder) user;

            boolean ok = AuctionManager.getInstance().registerAutoBid(
                auctionId, bidder,
                BigDecimal.valueOf(maxBid),
                BigDecimal.valueOf(increment)
            );

            return ok
                ? new Response("SUCCESS", "Đã đăng ký auto-bid thành công!", null)
                : new Response("FAIL", "Không thể đăng ký auto-bid (phiên đã kết thúc?)", null);

        } catch (Exception e) {
            logger.error("Lỗi AUTO_BID", e);
            return new Response("ERROR", "Dữ liệu không hợp lệ", null);
        }
    }

    // ─── Item management handlers ────────────────────────────────────────────

    private Response handleGetMyItems(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int sellerId = data.get("sellerId").getAsInt();
            java.util.List<ItemDAO.ItemRow> items = itemDAO.getItemsBySeller(sellerId);
            return new Response("SUCCESS", "OK", gson.toJson(items));
        } catch (Exception e) {
            logger.error("Lỗi GET_MY_ITEMS", e);
            return new Response("ERROR", "Không thể lấy danh sách sản phẩm", null);
        }
    }

    private Response handleAddItem(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int sellerId      = data.get("sellerId").getAsInt();
            String name       = data.get("name").getAsString();
            String desc       = data.has("description") ? data.get("description").getAsString() : "";
            double price      = data.get("startingPrice").getAsDouble();
            String category   = data.get("category").getAsString().toUpperCase();
            String extra1     = data.has("extra1") ? data.get("extra1").getAsString() : "";
            String extra2     = data.has("extra2") ? data.get("extra2").getAsString() : "";
            int    extraInt   = data.has("extraInt") ? data.get("extraInt").getAsInt() : 0;

            com.auction.shared.model.item.Item item;
            switch (category) {
                case "ART" ->
                    item = new com.auction.shared.model.item.Art(0, category, name, sellerId, desc, price, price, extra1, extra2);
                case "ELECTRONICS" ->
                    item = new com.auction.shared.model.item.Electronics(0, category, name, sellerId, desc, price, price, extra1, extraInt);
                case "VEHICLE" ->
                    item = new com.auction.shared.model.item.Vehicle(0, category, name, sellerId, desc, price, price, extraInt);
                default ->
                    item = new com.auction.shared.model.item.Art(0, category, name, sellerId, desc, price, price, "", "");
            }

            int newId = itemDAO.addItem(item, sellerId);
            if (newId > 0) {
                JsonObject result = new JsonObject();
                result.addProperty("itemId", newId);
                return new Response("SUCCESS", "Thêm sản phẩm thành công!", result.toString());
            } else {
                return new Response("FAIL", "Không thể thêm sản phẩm", null);
            }
        } catch (Exception e) {
            logger.error("Lỗi ADD_ITEM", e);
            return new Response("ERROR", "Dữ liệu không hợp lệ", null);
        }
    }

    private Response handleUpdateItem(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int    itemId   = data.get("itemId").getAsInt();
            int    sellerId = data.get("sellerId").getAsInt();
            String name     = data.get("name").getAsString();
            String desc     = data.has("description") ? data.get("description").getAsString() : "";
            double price    = data.get("startingPrice").getAsDouble();
            String category = data.get("category").getAsString().toUpperCase();
            String extra1   = data.has("extra1") ? data.get("extra1").getAsString() : null;
            String extra2   = data.has("extra2") ? data.get("extra2").getAsString() : null;

            boolean ok = itemDAO.updateItem(itemId, name, desc, price, category, extra1, extra2, sellerId);
            return ok
                ? new Response("SUCCESS", "Cập nhật sản phẩm thành công!", null)
                : new Response("FAIL", "Không thể cập nhật (không có quyền hoặc item không tồn tại)", null);
        } catch (Exception e) {
            logger.error("Lỗi UPDATE_ITEM", e);
            return new Response("ERROR", "Dữ liệu không hợp lệ", null);
        }
    }

    private Response handleDeleteItem(String payload) {
        try {
            JsonObject data = gson.fromJson(payload, JsonObject.class);
            int itemId   = data.get("itemId").getAsInt();
            int sellerId = data.get("sellerId").getAsInt();

            boolean ok = itemDAO.deleteItem(itemId, sellerId);
            return ok
                ? new Response("SUCCESS", "Xóa sản phẩm thành công!", null)
                : new Response("FAIL", "Không thể xóa! Sản phẩm đang trong phiên đấu giá hoặc không tồn tại.", null);
        } catch (Exception e) {
            logger.error("Lỗi DELETE_ITEM", e);
            return new Response("ERROR", "Dữ liệu không hợp lệ", null);
        }
    }

    private void closeConnections() {
        try {
            // Hủy toàn bộ subscription của client này khi disconnect
            if (out != null) {
                BidEventBus.getInstance().unsubscribeAll(out);
            }
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
            logger.info("Đã dọn dẹp luồng kết nối.");
        } catch (IOException e) {
            logger.error("Lỗi khi đóng kết nối", e);
        }
    }
}