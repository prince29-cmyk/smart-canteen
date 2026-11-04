import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Smart Canteen backend.
 * Serves the web interface from ./web and a REST API under /api/.
 *   /api/admin/...  staff only   (header X-Staff-Token)
 *   /api/my/...     student only (header X-Student-Token)
 * Requires JDK 17+ and the MySQL Connector/J jar.
 */
public class Main {

    static final Path WEB = Paths.get("web").toAbsolutePath().normalize();

    /** A student can get a refund when cancelling within this many seconds of ordering.
     *  Must match the 300 in trigger trg_order_before_update (canteen.sql). */
    static final int REFUND_WINDOW_SECONDS = 300;
    /** "Your order will be delivered in about N minutes" shown after ordering. */
    static final int DELIVERY_MINUTES = 5;
    /** How long a student stays logged in on a device. */
    static final int SESSION_DAYS = 30;

    static final SecureRandom RANDOM = new SecureRandom();

    /** Logged-in staff sessions: token -> staff row. Cleared when the server restarts. */
    static final Map<String, Map<String, Object>> STAFF_SESSIONS = new ConcurrentHashMap<>();

    /** Wrong-password counter: key -> {failures, lockedUntilMillis}. */
    static final Map<String, long[]> LOGIN_FAILS = new ConcurrentHashMap<>();

    /** Thrown when an endpoint is called without a valid login (HTTP 401). */
    static class AuthException extends RuntimeException {
        AuthException(String msg) { super(msg); }
    }

    static final String MENU_SQL =
            "SELECT m.item_id, m.item_name, c.category_name, m.price, m.stock, m.is_available, "
          + "EXISTS(SELECT 1 FROM favourites f WHERE f.item_id = m.item_id AND f.student_id = ?) AS is_favourite "
          + "FROM menu_items m JOIN categories c ON c.category_id = m.category_id "
          + "ORDER BY c.category_id, m.item_name";

    static final String PROFILE_SQL =
            "SELECT s.student_id, s.name, s.mobile, c.college_name, d.dept_name, s.wallet_balance "
          + "FROM students s JOIN colleges c ON c.college_id = s.college_id "
          + "LEFT JOIN departments d ON d.dept_id = s.dept_id ";

    // ------------------------------------------------------------------ startup
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL driver not found. Put mysql-connector-j-x.x.x.jar inside the lib folder.");
            return;
        }
        try (Connection c = Db.get()) {
            System.out.println("Connected to MySQL.");
        } catch (SQLException e) {
            System.err.println("Cannot connect to MySQL: " + e.getMessage());
            System.err.println("Check DB_USER / DB_PASS in src/Db.java (or environment variables) and that you ran database/canteen.sql.");
            return;
        }

        startDailyRollover();

        int port = Integer.parseInt(Db.env("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/api/", Main::handleApi);
        server.createContext("/", Main::handleStatic);
        server.start();
        System.out.println("Smart Canteen running at http://localhost:" + port);
    }

    /**
     * Daily refresh: writes a summary of every finished day into the audit log
     * (procedure sp_daily_rollover). Runs at start-up, then every midnight.
     * Token numbers restart from 1 on their own (trigger trg_orders_daily_no).
     */
    static void startDailyRollover() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-rollover");
            t.setDaemon(true);
            return t;
        });
        Runnable job = Main::runRollover;
        job.run();                                                    // catch up on missed days now
        LocalDateTime nextRun = LocalDate.now().plusDays(1).atTime(0, 0, 10);
        long wait = Math.max(1, Duration.between(LocalDateTime.now(), nextRun).getSeconds());
        scheduler.scheduleAtFixedRate(job, wait, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);
    }

    static void runRollover() {
        try {
            Db.call("CALL sp_daily_rollover()");
        } catch (SQLException e) {
            System.err.println("Daily rollover failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ API plumbing
    static void handleApi(HttpExchange ex) throws IOException {
        int status = 200;
        Object body;
        try {
            body = route(ex);
        } catch (AuthException e) {
            status = 401;
            body = error(e.getMessage());
        } catch (SQLException e) {
            status = 400;
            body = error(sqlMessage(e));
        } catch (NumberFormatException e) {
            status = 400;
            body = error("Invalid number in request");
        } catch (IllegalArgumentException e) {
            status = 400;
            body = error(e.getMessage());
        } catch (Exception e) {
            status = 500;
            body = error("Server error: " + e);
        }
        send(ex, status, "application/json; charset=utf-8",
                Json.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    static String sqlMessage(SQLException e) {
        if ("45000".equals(e.getSQLState())) return e.getMessage();   // our own trigger / procedure messages
        if (e.getErrorCode() == 1062) return "That entry already exists";
        if (e.getErrorCode() == 1452) return "Invalid reference in request";
        return "Database error: " + e.getMessage();
    }

    static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg == null ? "Unknown error" : msg);
        return m;
    }

    static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    // ------------------------------------------------------------------ authentication helpers
    static void requireStaff(HttpExchange ex) {
        String token = ex.getRequestHeaders().getFirst("X-Staff-Token");
        if (token == null || !STAFF_SESSIONS.containsKey(token))
            throw new AuthException("Please log in as staff");
    }

    /** Student id of the logged-in student, or 0 when there is no valid login. */
    static int currentStudent(HttpExchange ex) throws SQLException {
        String token = ex.getRequestHeaders().getFirst("X-Student-Token");
        if (token == null || token.length() != 64) return 0;
        List<Map<String, Object>> rows = Db.query(
                "SELECT student_id FROM student_sessions WHERE token = ? AND expires_at > NOW()", token);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("student_id")).intValue();
    }

    static int requireStudent(HttpExchange ex) throws SQLException {
        int id = currentStudent(ex);
        if (id == 0) throw new AuthException("Please log in");
        return id;
    }

    /** Blocks a login id for 5 minutes after 5 wrong passwords. */
    static void checkLock(String key) {
        long[] f = LOGIN_FAILS.get(key);
        long now = System.currentTimeMillis();
        if (f != null && f[1] > now) {
            long mins = Math.max(1, (f[1] - now + 59_999) / 60_000);
            throw new IllegalArgumentException("Too many wrong attempts. Try again in " + mins + " minute(s).");
        }
    }

    static void recordFail(String key) {
        LOGIN_FAILS.compute(key, (k, f) -> {
            long now = System.currentTimeMillis();
            if (f == null || (f[1] != 0 && f[1] <= now)) f = new long[]{0, 0};
            f[0]++;
            if (f[0] >= 5) { f[0] = 0; f[1] = now + 5 * 60_000L; }
            return f;
        });
    }

    // ------------------------------------------------------------------ routing
    static Object route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        String key = ex.getRequestMethod() + " " + path;
        Map<String, String> p = params(ex);

        if (path.startsWith("/api/admin/")) requireStaff(ex);
        int sid = (path.startsWith("/api/my/") || path.equals("/api/me")) ? requireStudent(ex) : 0;

        switch (key) {

            // ================= public =================
            case "GET /api/colleges":
                return Db.query("SELECT college_name FROM colleges ORDER BY college_name");

            case "GET /api/departments":
                return Db.query("SELECT dept_id, dept_name FROM departments ORDER BY dept_name");

            case "GET /api/customizations":
                return Db.query("SELECT custom_id, custom_name, extra_price FROM customizations "
                              + "WHERE is_active = 1 ORDER BY custom_name");

            case "GET /api/menu":
                return Db.query(MENU_SQL, currentStudent(ex));

            // ================= student account =================
            case "POST /api/student/register":
                return register(p);

            case "POST /api/student/login":
                return studentLogin(p);

            case "POST /api/student/logout": {
                String token = ex.getRequestHeaders().getFirst("X-Student-Token");
                if (token != null) Db.update("DELETE FROM student_sessions WHERE token = ?", token);
                return ok();
            }

            case "GET /api/me":
                return profile(sid);

            // ================= student (login required) =================
            case "POST /api/my/order":
                return placeOrder(sid, req(p, "items"), p.get("extras"), p.get("note"));

            case "GET /api/my/orders":
                return Db.query(ordersSql("WHERE o.student_id = ? AND o.status <> 'Pending'",
                        "o.order_id DESC LIMIT 100"), sid);

            case "GET /api/my/order-items": {
                int orderId = Integer.parseInt(req(p, "order_id"));
                List<Map<String, Object>> items = Db.query(
                        "SELECT oi.item_id, mi.item_name, oi.quantity, mi.stock, mi.is_available "
                      + "FROM order_items oi JOIN menu_items mi ON mi.item_id = oi.item_id "
                      + "JOIN orders o ON o.order_id = oi.order_id "
                      + "WHERE oi.order_id = ? AND o.student_id = ?", orderId, sid);
                if (items.isEmpty()) throw new IllegalArgumentException("Order not found");
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("items", items);
                out.put("extras", Db.query("SELECT custom_id FROM order_customizations WHERE order_id = ?", orderId));
                out.put("note", Db.query("SELECT special_note FROM orders WHERE order_id = ?", orderId)
                        .get(0).get("special_note"));
                return out;
            }

            case "POST /api/my/order/cancel": {
                int orderId = Integer.parseInt(req(p, "order_id"));
                // triggers decide the refund: within 5 minutes = full refund, later = no refund
                int n = Db.update("UPDATE orders SET status = 'Cancelled', cancelled_by = 'Student' "
                                + "WHERE order_id = ? AND student_id = ? AND status = 'Placed'", orderId, sid);
                if (n == 0) throw new IllegalArgumentException(
                        "This order can no longer be cancelled (the kitchen may have started preparing it)");
                Map<String, Object> out = ok();
                out.put("refund_amount", Db.query("SELECT refund_amount FROM orders WHERE order_id = ?", orderId)
                        .get(0).get("refund_amount"));
                return out;
            }

            case "GET /api/my/wallet": {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("balance", profile(sid).get("wallet_balance"));
                out.put("transactions", Db.query(
                        "SELECT txn_id, txn_type, amount, balance_after, description, order_id, "
                      + "DATE_FORMAT(created_at, '%d %b %Y, %h:%i %p') AS txn_time "
                      + "FROM wallet_transactions WHERE student_id = ? ORDER BY txn_id DESC LIMIT 200", sid));
                return out;
            }

            case "POST /api/my/wallet/topup": {
                BigDecimal amt = new BigDecimal(req(p, "amount"));
                if (amt.signum() <= 0 || amt.compareTo(new BigDecimal("5000")) > 0)
                    throw new IllegalArgumentException("Amount must be between 1 and 5000");
                // trigger trg_wallet_log writes the wallet statement row
                Db.update("UPDATE students SET wallet_balance = wallet_balance + ? WHERE student_id = ?", amt, sid);
                return profile(sid);
            }

            case "POST /api/my/favourite": {
                int itemId = Integer.parseInt(req(p, "item_id"));
                Map<String, Object> out = ok();
                if (Db.update("DELETE FROM favourites WHERE student_id = ? AND item_id = ?", sid, itemId) > 0) {
                    out.put("favourite", false);
                } else {
                    Db.update("INSERT INTO favourites(student_id, item_id) VALUES (?, ?)", sid, itemId);
                    out.put("favourite", true);
                }
                return out;
            }

            case "POST /api/my/feedback": {
                int orderId = Integer.parseInt(req(p, "order_id"));
                int rating = Integer.parseInt(req(p, "rating"));
                if (rating < 1 || rating > 5) throw new IllegalArgumentException("Rating must be 1 to 5");
                String text = p.get("review") == null ? "" : p.get("review").trim();
                if (text.length() > 300) throw new IllegalArgumentException("Feedback is too long (max 300 characters)");
                try {
                    Db.update("INSERT INTO feedback(order_id, student_id, rating, review_text) VALUES (?,?,?,?)",
                            orderId, sid, rating, text.isEmpty() ? null : text);
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1062) throw new IllegalArgumentException("You have already rated this order");
                    throw e;
                }
                return ok();
            }

            // ================= staff login =================
            case "POST /api/staff/login": {
                String code = req(p, "staff_code").trim().toUpperCase();
                checkLock("staff:" + code);
                List<Map<String, Object>> rows = Db.query(
                        "SELECT staff_id, staff_code, name, role FROM staff "
                      + "WHERE staff_code = ? AND password_hash = SHA2(?, 256) AND is_active = 1",
                        code, req(p, "password"));
                if (rows.isEmpty()) {
                    recordFail("staff:" + code);
                    throw new IllegalArgumentException("Invalid staff ID or password");
                }
                LOGIN_FAILS.remove("staff:" + code);
                Map<String, Object> staff = rows.get(0);
                String token = newToken();
                STAFF_SESSIONS.put(token, staff);
                Db.update("INSERT INTO audit_log(table_name, action, description) VALUES ('staff','LOGIN',?)",
                        staff.get("name") + " (" + staff.get("staff_code") + ") logged in");
                Map<String, Object> out = new LinkedHashMap<>(staff);
                out.put("token", token);
                return out;
            }

            case "POST /api/staff/logout": {
                String token = ex.getRequestHeaders().getFirst("X-Staff-Token");
                if (token != null) STAFF_SESSIONS.remove(token);
                return ok();
            }

            // ================= staff (login required) =================
            case "GET /api/admin/orders":
                // today's orders, plus any older order that is still not finished
                return Db.query(ordersSql(
                        "WHERE o.status <> 'Pending' AND (DATE(o.order_date) = CURDATE() "
                      + "OR o.status IN ('Placed','Preparing','Ready'))",
                        "CASE WHEN o.status IN ('Placed','Preparing') THEN 0 WHEN o.status = 'Ready' THEN 1 ELSE 2 END, "
                      + "CASE WHEN o.status IN ('Placed','Preparing') THEN o.order_id END ASC, o.order_id DESC"));

            case "POST /api/admin/order-status": {
                String st = req(p, "status");
                String from;
                switch (st) {
                    case "Preparing": from = "Placed";    break;
                    case "Ready":     from = "Preparing"; break;
                    case "Completed": from = "Ready";     break;
                    default: throw new IllegalArgumentException("Invalid status");
                }
                int n = Db.update("UPDATE orders SET status = ? WHERE order_id = ? AND status = ?",
                        st, Integer.parseInt(req(p, "order_id")), from);
                if (n == 0) throw new IllegalArgumentException("Order must be '" + from + "' before it can be marked " + st);
                return ok();
            }

            case "POST /api/admin/cancel": {
                // staff cancellations are always refunded (trigger trg_order_before_update)
                int n = Db.update("UPDATE orders SET status = 'Cancelled', cancelled_by = 'Staff' "
                                + "WHERE order_id = ? AND status IN ('Placed','Preparing','Ready')",
                        Integer.parseInt(req(p, "order_id")));
                if (n == 0) throw new IllegalArgumentException("Only Placed, Preparing or Ready orders can be cancelled");
                return ok();
            }

            case "POST /api/admin/stock": {
                int stock = Integer.parseInt(req(p, "stock"));
                if (stock < 0) throw new IllegalArgumentException("Stock cannot be negative");
                Db.update("UPDATE menu_items SET stock = ? WHERE item_id = ?", stock, Integer.parseInt(req(p, "item_id")));
                return ok();   // trigger trg_menu_before_update flips is_available automatically
            }

            case "POST /api/admin/price": {
                BigDecimal price = new BigDecimal(req(p, "price"));
                if (price.signum() <= 0) throw new IllegalArgumentException("Price must be greater than 0");
                Db.update("UPDATE menu_items SET price = ? WHERE item_id = ?", price, Integer.parseInt(req(p, "item_id")));
                return ok();   // trigger trg_menu_price_audit writes to audit_log
            }

            // ---- add-on price fixing (spice, mayonnaise, onion ...) ----
            case "GET /api/admin/customizations":
                return Db.query("SELECT custom_id, custom_name, extra_price, is_active FROM customizations "
                              + "ORDER BY custom_name");

            case "POST /api/admin/custom-price": {
                BigDecimal price = addonPrice(req(p, "price"));
                Db.update("UPDATE customizations SET extra_price = ? WHERE custom_id = ?",
                        price, Integer.parseInt(req(p, "custom_id")));
                return ok();   // trigger trg_custom_price_audit logs the change
            }

            case "POST /api/admin/custom-toggle":
                Db.update("UPDATE customizations SET is_active = 1 - is_active WHERE custom_id = ?",
                        Integer.parseInt(req(p, "custom_id")));
                return ok();

            case "POST /api/admin/custom-add": {
                String name = clean(req(p, "name"), 2, 50, "Add-on name");
                BigDecimal price = addonPrice(req(p, "price"));
                try {
                    Db.update("INSERT INTO customizations(custom_name, extra_price) VALUES (?, ?)", name, price);
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1062) throw new IllegalArgumentException("That add-on already exists");
                    throw e;
                }
                return ok();
            }

            // ---- audit log, one day at a time ----
            case "GET /api/admin/audit-days": {
                runRollover();   // make sure finished days have their summary line
                return Db.query("SELECT DATE_FORMAT(logged_at, '%Y-%m-%d') AS log_day, COUNT(*) AS entries "
                              + "FROM audit_log GROUP BY DATE_FORMAT(logged_at, '%Y-%m-%d') "
                              + "ORDER BY log_day DESC LIMIT 60");
            }

            case "GET /api/admin/audit": {
                LocalDate day;
                try {
                    day = LocalDate.parse(req(p, "date"));
                } catch (DateTimeException e) {
                    throw new IllegalArgumentException("Invalid date");
                }
                java.sql.Date d = java.sql.Date.valueOf(day);
                return Db.query("SELECT log_id, table_name, action, description, "
                              + "DATE_FORMAT(logged_at, '%h:%i:%s %p') AS at_time "
                              + "FROM audit_log WHERE logged_at >= ? AND logged_at < DATE_ADD(?, INTERVAL 1 DAY) "
                              + "ORDER BY logged_at DESC, log_id DESC LIMIT 500", d, d);
            }

            case "GET /api/admin/feedback": {
                Map<String, Object> out = new LinkedHashMap<>();
                Map<String, Object> sum = Db.query("SELECT COUNT(*) AS total, AVG(rating) AS average FROM feedback").get(0);
                out.put("total", sum.get("total"));
                out.put("average", sum.get("average"));
                out.put("recent", Db.query(
                        "SELECT f.rating, f.review_text, s.name AS student_name, o.daily_no, "
                      + "DATE_FORMAT(o.order_date, '%d %b %Y') AS order_day, "
                      + "DATE_FORMAT(f.created_at, '%d %b %Y, %h:%i %p') AS rated_at "
                      + "FROM feedback f JOIN orders o ON o.order_id = f.order_id "
                      + "JOIN students s ON s.student_id = f.student_id "
                      + "ORDER BY f.created_at DESC LIMIT 50"));
                return out;
            }

            default:
                throw new IllegalArgumentException("Unknown endpoint: " + key);
        }
    }

    // ------------------------------------------------------------------ student accounts
    static Map<String, Object> profile(int studentId) throws SQLException {
        List<Map<String, Object>> rows = Db.query(PROFILE_SQL + "WHERE s.student_id = ?", studentId);
        if (rows.isEmpty()) throw new AuthException("Please log in");
        return rows.get(0);
    }

    static Map<String, Object> register(Map<String, String> p) throws SQLException {
        String mobile = normalizeMobile(req(p, "mobile"));
        String name = clean(req(p, "name"), 2, 80, "Name");
        String college = clean(req(p, "college"), 2, 120, "College name");
        String password = req(p, "password");
        if (password.length() < 6 || password.length() > 64)
            throw new IllegalArgumentException("Password must be 6 to 64 characters");
        String deptParam = p.get("dept_id");
        Integer dept = (deptParam == null || deptParam.isBlank()) ? null : Integer.valueOf(deptParam.trim());

        // students from ANY college can join: an unknown college is added to the list
        Db.update("INSERT IGNORE INTO colleges(college_name) VALUES (?)", college);
        int collegeId = ((Number) Db.query("SELECT college_id FROM colleges WHERE college_name = ?", college)
                .get(0).get("college_id")).intValue();

        try {
            Db.update("INSERT INTO students(mobile, name, college_id, dept_id, password_hash) VALUES (?,?,?,?,?)",
                    mobile, name, collegeId, dept, Passwords.hash(password));
        } catch (SQLException e) {
            if (e.getErrorCode() == 1062)
                throw new IllegalArgumentException("This mobile number is already registered. Please log in.");
            throw e;
        }
        int id = ((Number) Db.query("SELECT student_id FROM students WHERE mobile = ?", mobile)
                .get(0).get("student_id")).intValue();
        return startSession(id);
    }

    static Map<String, Object> studentLogin(Map<String, String> p) throws SQLException {
        String mobile = normalizeMobile(req(p, "mobile"));
        String lockKey = "student:" + mobile;
        checkLock(lockKey);
        List<Map<String, Object>> rows = Db.query(
                "SELECT student_id, password_hash FROM students WHERE mobile = ?", mobile);
        boolean good = !rows.isEmpty()
                && Passwords.verify(req(p, "password"), (String) rows.get(0).get("password_hash"));
        if (!good) {
            recordFail(lockKey);
            throw new IllegalArgumentException("Wrong mobile number or password");
        }
        LOGIN_FAILS.remove(lockKey);
        return startSession(((Number) rows.get(0).get("student_id")).intValue());
    }

    /** Creates a login that is saved on the student's device for SESSION_DAYS days. */
    static Map<String, Object> startSession(int studentId) throws SQLException {
        String token = newToken();
        Db.update("DELETE FROM student_sessions WHERE expires_at < NOW()");
        Db.update("INSERT INTO student_sessions(token, student_id, expires_at) "
                + "VALUES (?, ?, DATE_ADD(NOW(), INTERVAL " + SESSION_DAYS + " DAY))", token, studentId);
        Map<String, Object> out = new LinkedHashMap<>(profile(studentId));
        out.put("token", token);
        return out;
    }

    static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);      // 64 characters
    }

    static String normalizeMobile(String raw) {
        String m = raw.replaceAll("\\D", "");
        if (m.length() == 12 && m.startsWith("91")) m = m.substring(2);
        if (m.length() == 11 && m.startsWith("0")) m = m.substring(1);
        if (!m.matches("[6-9]\\d{9}")) throw new IllegalArgumentException("Enter a valid 10-digit mobile number");
        return m;
    }

    static String clean(String s, int min, int max, String label) {
        String v = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (v.length() < min || v.length() > max)
            throw new IllegalArgumentException(label + " must be " + min + " to " + max + " characters");
        return v;
    }

    static BigDecimal addonPrice(String raw) {
        BigDecimal price = new BigDecimal(raw);
        if (price.signum() < 0 || price.compareTo(new BigDecimal("500")) > 0)
            throw new IllegalArgumentException("Price must be between 0 and 500");
        return price;
    }

    // ------------------------------------------------------------------ orders
    /**
     * Orders list with live queue info.
     *  queue_position      = Placed/Preparing orders up to and including this one (oldest first)
     *  eta_minutes         = DELIVERY_MINUTES while the order is in the queue
     *  refund_seconds_left = time left to cancel WITH refund (only while Placed)
     */
    static String ordersSql(String where, String orderBy) {
        String inQueue = "o.status IN ('Placed','Preparing')";
        return "SELECT o.order_id, o.daily_no, "
             + "DATE_FORMAT(o.order_date, '%d %b %Y, %h:%i %p') AS order_time, "
             + "(DATE(o.order_date) = CURDATE()) AS is_today, "
             + "o.status, o.total_amount, o.special_note, o.refund_amount, o.cancelled_by, "
             + "s.name AS student_name, s.mobile, "
             + "(SELECT GROUP_CONCAT(CONCAT(mi.item_name, ' x', oi.quantity) ORDER BY mi.item_name SEPARATOR ', ') "
             + "   FROM order_items oi JOIN menu_items mi ON mi.item_id = oi.item_id "
             + "   WHERE oi.order_id = o.order_id) AS items, "
             + "(SELECT GROUP_CONCAT(CONCAT(c.custom_name, '|', oc.price_charged) ORDER BY c.custom_name SEPARATOR ';') "
             + "   FROM order_customizations oc JOIN customizations c ON c.custom_id = oc.custom_id "
             + "   WHERE oc.order_id = o.order_id) AS extras, "
             + "CASE WHEN " + inQueue + " THEN (SELECT COUNT(*) FROM orders q "
             + "   WHERE q.status IN ('Placed','Preparing') AND q.order_id <= o.order_id) END AS queue_position, "
             + "CASE WHEN " + inQueue + " THEN " + DELIVERY_MINUTES + " END AS eta_minutes, "
             + "CASE WHEN o.status = 'Placed' THEN GREATEST(0, " + REFUND_WINDOW_SECONDS
             + " - TIMESTAMPDIFF(SECOND, o.order_date, NOW())) END AS refund_seconds_left, "
             + "(SELECT f.rating FROM feedback f WHERE f.order_id = o.order_id) AS rating, "
             + "(SELECT f.review_text FROM feedback f WHERE f.order_id = o.order_id) AS review_text "
             + "FROM orders o JOIN students s ON s.student_id = o.student_id "
             + where + " ORDER BY " + orderBy;
    }

    /**
     * TRANSACTION: create order + items + add-ons + wallet payment as ONE unit.
     * Triggers fire inside it (token number, stock check, prices, totals, wallet statement).
     * Any failure (no stock, low balance, ...) rolls everything back.
     */
    static Map<String, Object> placeOrder(int studentId, String itemsParam, String extrasParam, String noteParam)
            throws SQLException {
        String note = noteParam == null ? "" : noteParam.trim();
        if (note.length() > 200) throw new IllegalArgumentException("Note is too long (max 200 characters)");

        Map<Integer, Integer> cart = new TreeMap<>();   // sorted -> consistent lock order
        for (String part : itemsParam.split(",")) {
            if (part.isBlank()) continue;
            String[] kv = part.split(":");
            if (kv.length != 2) throw new IllegalArgumentException("Bad items format");
            int itemId = Integer.parseInt(kv[0].trim());
            int qty = Integer.parseInt(kv[1].trim());
            if (qty <= 0) throw new IllegalArgumentException("Quantity must be at least 1");
            cart.merge(itemId, qty, Integer::sum);
        }
        if (cart.isEmpty()) throw new IllegalArgumentException("Your cart is empty");

        Set<Integer> extras = new TreeSet<>();
        if (extrasParam != null) {
            for (String part : extrasParam.split(",")) {
                if (!part.isBlank()) extras.add(Integer.parseInt(part.trim()));
            }
        }

        try (Connection c = Db.get()) {
            try {
                c.setAutoCommit(false);                                   // START TRANSACTION

                int orderId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO orders(student_id, special_note) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, studentId);
                    ps.setString(2, note.isEmpty() ? null : note);
                    ps.executeUpdate();                                   // trigger gives today's token number
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        orderId = keys.getInt(1);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO order_items(order_id, item_id, quantity) VALUES (?,?,?)")) {
                    for (Map.Entry<Integer, Integer> e : cart.entrySet()) {
                        ps.setInt(1, orderId);
                        ps.setInt(2, e.getKey());
                        ps.setInt(3, e.getValue());
                        ps.executeUpdate();                               // stock + price triggers fire here
                    }
                }

                if (!extras.isEmpty()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO order_customizations(order_id, custom_id) VALUES (?,?)")) {
                        for (int customId : extras) {
                            ps.setInt(1, orderId);
                            ps.setInt(2, customId);
                            ps.executeUpdate();                           // add-on price trigger fires here
                        }
                    }
                }

                BigDecimal total;
                int token;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT total_amount, daily_no FROM orders WHERE order_id = ?")) {
                    ps.setInt(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        total = rs.getBigDecimal(1);
                        token = rs.getInt(2);
                    }
                }

                // tell the wallet trigger what this payment is for (statement line)
                try (Statement st = c.createStatement()) {
                    st.execute("SET @wallet_type = 'PAYMENT', @wallet_order_id = " + orderId
                             + ", @wallet_note = 'Payment for token #" + token + "'");
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE students SET wallet_balance = wallet_balance - ? "
                      + "WHERE student_id = ? AND wallet_balance >= ?")) {
                    ps.setBigDecimal(1, total);
                    ps.setInt(2, studentId);
                    ps.setBigDecimal(3, total);
                    if (ps.executeUpdate() == 0)
                        throw new SQLException("Insufficient wallet balance", "45000");
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE orders SET status = 'Placed' WHERE order_id = ?")) {
                    ps.setInt(1, orderId);
                    ps.executeUpdate();
                }

                // Queue position = Placed/Preparing orders up to and including this one
                int queuePosition;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM orders WHERE status IN ('Placed','Preparing') AND order_id <= ?")) {
                    ps.setInt(1, orderId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        queuePosition = rs.getInt(1);
                    }
                }

                String studentName;
                BigDecimal balance;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT name, wallet_balance FROM students WHERE student_id = ?")) {
                    ps.setInt(1, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        studentName = rs.getString(1);
                        balance = rs.getBigDecimal(2);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO audit_log(table_name, action, description) VALUES ('orders','ORDER_PLACED',?)")) {
                    ps.setString(1, "Token #" + token + " (order " + orderId + ") placed by " + studentName
                            + ", total Rs " + total);
                    ps.executeUpdate();
                }

                c.commit();                                               // COMMIT

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("order_id", orderId);
                result.put("daily_no", token);
                result.put("total", total);
                result.put("wallet_balance", balance);
                result.put("queue_position", queuePosition);
                result.put("eta_minutes", DELIVERY_MINUTES);
                return result;

            } catch (SQLException | RuntimeException e) {
                c.rollback();                                             // ROLLBACK
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ request helpers
    static String req(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing field: " + name);
        return v;
    }

    static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<>();
        parseInto(m, ex.getRequestURI().getRawQuery());
        if ("POST".equals(ex.getRequestMethod())) {
            parseInto(m, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void parseInto(Map<String, String> m, String s) {
        if (s == null || s.isEmpty()) return;
        for (String pair : s.split("&")) {
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
    }

    // ------------------------------------------------------------------ static files
    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        try {
            Path file = WEB.resolve(path.substring(1)).normalize();
            if (file.startsWith(WEB) && Files.isRegularFile(file)) {
                String name = file.getFileName().toString();
                String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                            : name.endsWith(".css")  ? "text/css; charset=utf-8"
                            : name.endsWith(".js")   ? "application/javascript; charset=utf-8"
                            : "application/octet-stream";
                send(ex, 200, type, Files.readAllBytes(file));
                return;
            }
        } catch (RuntimeException ignored) {
            // invalid path -> fall through to 404
        }
        send(ex, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");   // always show the newest page
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
