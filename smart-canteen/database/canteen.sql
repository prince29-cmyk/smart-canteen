-- =====================================================================
--  SMART CANTEEN SYSTEM  (MySQL 8)
--  Normalized schema (3NF), triggers, transactions, daily audit log.
--
--  RUN THIS WHOLE FILE ONCE in MySQL Workbench (lightning bolt, nothing selected).
--  WARNING: it drops and recreates the database, so all old data is reset.
--
--  Demo logins after running:
--    Students (mobile / password):  9876543210 / demo123   (Arun)
--                                   9876501234 / demo123   (Priya)
--                                   9812345678 / demo123   (Karthik)
--    Staff (staff id / password):   STF001 / admin123      (Manager)
--                                   STF002 / kitchen456    (Cook)
-- =====================================================================

DROP DATABASE IF EXISTS smart_canteen;
CREATE DATABASE smart_canteen;
USE smart_canteen;

-- =====================================================================
-- PART 1: NORMALIZATION (how the design was reached)
-- =====================================================================
-- UNNORMALIZED (UNF): one big table
--   Orders(order_id, student_name, mobile, college_name, dept_name, order_date,
--          items[ item_name, category, price, qty ], addons[ name, price ], total)
--   Problems: repeating groups (items, add-ons); student, college and department
--   data repeated in every order; item price/category repeated; update, insert
--   and delete anomalies.
--
-- 1NF : Remove repeating groups -> every column atomic.
--       Items become rows (order_id, item_id, quantity).
--       Add-ons become rows (order_id, custom_id).
--
-- 2NF : Remove partial dependencies (on part of a composite key).
--       item_name, price depend only on item_id        -> MENU_ITEMS
--       add-on name, price depend only on custom_id    -> CUSTOMIZATIONS
--
-- 3NF : Remove transitive dependencies (non-key -> non-key).
--       student -> college_name via college_id   -> COLLEGES
--       student -> dept_name    via dept_id      -> DEPARTMENTS
--       item    -> category_name via category_id -> CATEGORIES
--
-- Prices copied into order_items.unit_price and order_customizations.price_charged
-- are NOT redundancy: they are the price AT THE TIME of purchase (history).
-- =====================================================================

-- ---------------------------------------------------------------------
-- PART 2: TABLES
-- ---------------------------------------------------------------------
CREATE TABLE colleges (
    college_id    INT AUTO_INCREMENT PRIMARY KEY,
    college_name  VARCHAR(120) NOT NULL UNIQUE
);

CREATE TABLE departments (
    dept_id     INT AUTO_INCREMENT PRIMARY KEY,
    dept_name   VARCHAR(60) NOT NULL UNIQUE
);

-- Students from ANY college can register with a mobile number.
CREATE TABLE students (
    student_id      INT AUTO_INCREMENT PRIMARY KEY,
    mobile          CHAR(10)     NOT NULL UNIQUE,          -- login id
    name            VARCHAR(80)  NOT NULL,
    college_id      INT          NOT NULL,
    dept_id         INT          NULL,                     -- optional
    roll_no         VARCHAR(20)  NULL,                     -- optional
    password_hash   VARCHAR(200) NOT NULL,                 -- salted PBKDF2, never plain text
    wallet_balance  DECIMAL(10,2) NOT NULL DEFAULT 0.00 CHECK (wallet_balance >= 0),
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (college_id) REFERENCES colleges(college_id),
    FOREIGN KEY (dept_id)    REFERENCES departments(dept_id)
);

-- "Keep me logged in": one row per logged-in device, valid for 30 days.
CREATE TABLE student_sessions (
    token       CHAR(64) PRIMARY KEY,
    student_id  INT NOT NULL,
    expires_at  DATETIME NOT NULL,
    FOREIGN KEY (student_id) REFERENCES students(student_id) ON DELETE CASCADE
);

CREATE TABLE categories (
    category_id    INT AUTO_INCREMENT PRIMARY KEY,
    category_name  VARCHAR(50) NOT NULL UNIQUE
);

CREATE TABLE menu_items (
    item_id        INT AUTO_INCREMENT PRIMARY KEY,
    item_name      VARCHAR(80) NOT NULL UNIQUE,
    category_id    INT NOT NULL,
    price          DECIMAL(8,2) NOT NULL CHECK (price > 0),
    stock          INT NOT NULL DEFAULT 0 CHECK (stock >= 0),
    is_available   TINYINT(1) NOT NULL DEFAULT 1,
    FOREIGN KEY (category_id) REFERENCES categories(category_id)
);

-- PRICE FIXING TABLE for specifications (spice, mayonnaise, onion ...).
-- Staff decide the extra price; 0 means free.
CREATE TABLE customizations (
    custom_id    INT AUTO_INCREMENT PRIMARY KEY,
    custom_name  VARCHAR(50) NOT NULL UNIQUE,
    extra_price  DECIMAL(6,2) NOT NULL DEFAULT 0.00 CHECK (extra_price >= 0),
    is_active    TINYINT(1) NOT NULL DEFAULT 1
);

CREATE TABLE orders (
    order_id       INT AUTO_INCREMENT PRIMARY KEY,
    daily_no       INT NOT NULL DEFAULT 0,       -- token number, restarts at 1 every day (trigger)
    student_id     INT NOT NULL,
    order_date     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status         ENUM('Pending','Placed','Preparing','Ready','Completed','Cancelled') NOT NULL DEFAULT 'Pending',
    total_amount   DECIMAL(10,2) NOT NULL DEFAULT 0.00,   -- items + add-ons (triggers)
    special_note   VARCHAR(200) NULL,
    cancelled_by   ENUM('Student','Staff') NULL,
    refund_amount  DECIMAL(10,2) NOT NULL DEFAULT 0.00,   -- set by trigger on cancel
    FOREIGN KEY (student_id) REFERENCES students(student_id),
    INDEX idx_orders_date (order_date)
);

CREATE TABLE order_items (
    order_id    INT NOT NULL,
    item_id     INT NOT NULL,
    quantity    INT NOT NULL CHECK (quantity > 0),
    unit_price  DECIMAL(8,2) NOT NULL DEFAULT 0.00,   -- filled by trigger
    PRIMARY KEY (order_id, item_id),
    FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE CASCADE,
    FOREIGN KEY (item_id)  REFERENCES menu_items(item_id)
);

CREATE TABLE order_customizations (
    order_id       INT NOT NULL,
    custom_id      INT NOT NULL,
    price_charged  DECIMAL(6,2) NOT NULL DEFAULT 0.00,   -- filled by trigger
    PRIMARY KEY (order_id, custom_id),
    FOREIGN KEY (order_id)  REFERENCES orders(order_id) ON DELETE CASCADE,
    FOREIGN KEY (custom_id) REFERENCES customizations(custom_id)
);

CREATE TABLE favourites (
    student_id  INT NOT NULL,
    item_id     INT NOT NULL,
    PRIMARY KEY (student_id, item_id),
    FOREIGN KEY (student_id) REFERENCES students(student_id) ON DELETE CASCADE,
    FOREIGN KEY (item_id)    REFERENCES menu_items(item_id)  ON DELETE CASCADE
);

CREATE TABLE feedback (
    order_id    INT PRIMARY KEY,                       -- one rating per order
    student_id  INT NOT NULL,
    rating      TINYINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    review_text  VARCHAR(300) NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (order_id)   REFERENCES orders(order_id),
    FOREIGN KEY (student_id) REFERENCES students(student_id)
);

-- Wallet statement: every top-up, payment and refund (filled by a trigger).
CREATE TABLE wallet_transactions (
    txn_id         INT AUTO_INCREMENT PRIMARY KEY,
    student_id     INT NOT NULL,
    txn_type       ENUM('TOPUP','PAYMENT','REFUND') NOT NULL,
    amount         DECIMAL(10,2) NOT NULL,             -- always positive
    balance_after  DECIMAL(10,2) NOT NULL,
    order_id       INT NULL,
    description    VARCHAR(120) NULL,
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (student_id) REFERENCES students(student_id),
    FOREIGN KEY (order_id)   REFERENCES orders(order_id),
    INDEX idx_wallet_student (student_id, txn_id)
);

-- Last token number used on each day (so numbering restarts from 1 every day).
CREATE TABLE daily_counter (
    counter_date  DATE PRIMARY KEY,
    last_no       INT NOT NULL
);

CREATE TABLE audit_log (
    log_id       INT AUTO_INCREMENT PRIMARY KEY,
    table_name   VARCHAR(40) NOT NULL,
    action       VARCHAR(30) NOT NULL,
    description  VARCHAR(255),
    logged_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_audit_time (logged_at)
);

CREATE TABLE staff (
    staff_id       INT AUTO_INCREMENT PRIMARY KEY,
    staff_code     VARCHAR(20)  NOT NULL UNIQUE,
    name           VARCHAR(80)  NOT NULL,
    role           ENUM('Manager','Cashier','Cook') NOT NULL DEFAULT 'Cashier',
    password_hash  CHAR(64)     NOT NULL,
    is_active      TINYINT(1)   NOT NULL DEFAULT 1
);

-- ---------------------------------------------------------------------
-- PART 3: TRIGGERS
-- ---------------------------------------------------------------------
DELIMITER //

-- T0: BEFORE INSERT on orders
--     Gives the order a token number that restarts from 1 every day.
CREATE TRIGGER trg_orders_daily_no
BEFORE INSERT ON orders
FOR EACH ROW
BEGIN
    DECLARE v_no INT;
    INSERT INTO daily_counter(counter_date, last_no) VALUES (CURDATE(), 1)
        ON DUPLICATE KEY UPDATE last_no = last_no + 1;
    SELECT last_no INTO v_no FROM daily_counter WHERE counter_date = CURDATE();
    SET NEW.daily_no = v_no;
END//

-- T1: BEFORE INSERT on order_items
--     Validates stock/availability and copies the current price.
CREATE TRIGGER trg_order_item_before_insert
BEFORE INSERT ON order_items
FOR EACH ROW
BEGIN
    DECLARE v_stock INT;
    DECLARE v_price DECIMAL(8,2);
    DECLARE v_avail TINYINT;

    SELECT stock, price, is_available INTO v_stock, v_price, v_avail
    FROM menu_items WHERE item_id = NEW.item_id;

    IF v_avail = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Item is not available';
    END IF;
    IF v_stock < NEW.quantity THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Insufficient stock for this item';
    END IF;

    SET NEW.unit_price = v_price;
END//

-- T2: AFTER INSERT on order_items
--     Reduces stock and adds the item amount to the order total.
CREATE TRIGGER trg_order_item_after_insert
AFTER INSERT ON order_items
FOR EACH ROW
BEGIN
    UPDATE menu_items SET stock = stock - NEW.quantity WHERE item_id = NEW.item_id;
    UPDATE orders SET total_amount = total_amount + (NEW.quantity * NEW.unit_price)
    WHERE order_id = NEW.order_id;
END//

-- T3: BEFORE INSERT on order_customizations
--     Checks the add-on is active and copies the price fixed by staff.
CREATE TRIGGER trg_custom_before_insert
BEFORE INSERT ON order_customizations
FOR EACH ROW
BEGIN
    DECLARE v_price  DECIMAL(6,2);
    DECLARE v_active TINYINT;

    SELECT extra_price, is_active INTO v_price, v_active
    FROM customizations WHERE custom_id = NEW.custom_id;

    IF v_active IS NULL OR v_active = 0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'This add-on is not available';
    END IF;

    SET NEW.price_charged = v_price;
END//

-- T4: AFTER INSERT on order_customizations
--     Adds the add-on price to the order total.
CREATE TRIGGER trg_custom_after_insert
AFTER INSERT ON order_customizations
FOR EACH ROW
BEGIN
    UPDATE orders SET total_amount = total_amount + NEW.price_charged
    WHERE order_id = NEW.order_id;
END//

-- T5: BEFORE UPDATE on menu_items
--     Auto-marks item unavailable at 0 stock, available again on restock.
CREATE TRIGGER trg_menu_before_update
BEFORE UPDATE ON menu_items
FOR EACH ROW
BEGIN
    IF NEW.stock = 0 THEN
        SET NEW.is_available = 0;
    ELSEIF OLD.stock = 0 AND NEW.stock > 0 THEN
        SET NEW.is_available = 1;
    END IF;
END//

-- T6: AFTER UPDATE on menu_items
--     Audit trail for price changes.
CREATE TRIGGER trg_menu_price_audit
AFTER UPDATE ON menu_items
FOR EACH ROW
BEGIN
    IF OLD.price <> NEW.price THEN
        INSERT INTO audit_log(table_name, action, description)
        VALUES ('menu_items', 'PRICE_CHANGE',
                CONCAT(NEW.item_name, ': ', OLD.price, ' -> ', NEW.price));
    END IF;
END//

-- T7: AFTER UPDATE on customizations
--     Audit trail for add-on price changes made by staff.
CREATE TRIGGER trg_custom_price_audit
AFTER UPDATE ON customizations
FOR EACH ROW
BEGIN
    IF OLD.extra_price <> NEW.extra_price THEN
        INSERT INTO audit_log(table_name, action, description)
        VALUES ('customizations', 'ADDON_PRICE',
                CONCAT(NEW.custom_name, ': ', OLD.extra_price, ' -> ', NEW.extra_price));
    END IF;
END//

-- T8: BEFORE UPDATE on orders
--     REFUND RULE: a paid order cancelled within 5 minutes of ordering gets a full
--     refund. After 5 minutes the student can still cancel, but gets NO refund.
--     If the STAFF cancel an order, it is always refunded.
CREATE TRIGGER trg_order_before_update
BEFORE UPDATE ON orders
FOR EACH ROW
BEGIN
    IF OLD.status <> 'Cancelled' AND NEW.status = 'Cancelled' THEN
        IF OLD.status IN ('Placed','Preparing','Ready')
           AND (NEW.cancelled_by = 'Staff'
                OR TIMESTAMPDIFF(SECOND, OLD.order_date, NOW()) <= 300) THEN
            SET NEW.refund_amount = OLD.total_amount;
        ELSE
            SET NEW.refund_amount = 0;
        END IF;
    END IF;
END//

-- T9: AFTER UPDATE on orders
--     On cancellation: restore stock, refund the wallet (if eligible), log it.
CREATE TRIGGER trg_order_cancel
AFTER UPDATE ON orders
FOR EACH ROW
BEGIN
    IF OLD.status <> 'Cancelled' AND NEW.status = 'Cancelled' THEN
        UPDATE menu_items m
        JOIN order_items oi ON oi.item_id = m.item_id
        SET m.stock = m.stock + oi.quantity
        WHERE oi.order_id = NEW.order_id;

        IF NEW.refund_amount > 0 THEN
            SET @wallet_type = 'REFUND';
            SET @wallet_order_id = NEW.order_id;
            SET @wallet_note = CONCAT('Refund for token #', NEW.daily_no);
            UPDATE students SET wallet_balance = wallet_balance + NEW.refund_amount
            WHERE student_id = NEW.student_id;
            SET @wallet_type = NULL;
            SET @wallet_order_id = NULL;
            SET @wallet_note = NULL;
        END IF;

        INSERT INTO audit_log(table_name, action, description)
        VALUES ('orders', 'CANCELLED',
                CONCAT('Token #', NEW.daily_no, ' (order ', NEW.order_id, ') cancelled by ',
                       COALESCE(NEW.cancelled_by, 'system'),
                       IF(NEW.refund_amount > 0,
                          CONCAT(', refunded Rs ', NEW.refund_amount),
                          ', no refund')));
    END IF;
END//

-- T10: AFTER UPDATE on students
--      WALLET STATEMENT: every change of wallet_balance is written to
--      wallet_transactions. The caller may set @wallet_type / @wallet_order_id /
--      @wallet_note before the UPDATE; otherwise the type is guessed from the sign.
CREATE TRIGGER trg_wallet_log
AFTER UPDATE ON students
FOR EACH ROW
BEGIN
    IF NEW.wallet_balance <> OLD.wallet_balance THEN
        INSERT INTO wallet_transactions(student_id, txn_type, amount, balance_after, order_id, description)
        VALUES (NEW.student_id,
                COALESCE(@wallet_type, IF(NEW.wallet_balance > OLD.wallet_balance, 'TOPUP', 'PAYMENT')),
                ABS(NEW.wallet_balance - OLD.wallet_balance),
                NEW.wallet_balance,
                @wallet_order_id,
                COALESCE(@wallet_note,
                         IF(NEW.wallet_balance > OLD.wallet_balance, 'Wallet top-up', 'Wallet debit')));
    END IF;
END//

-- T11: BEFORE INSERT on feedback
--      Only the student who placed the order can rate it, and only once it is Completed.
CREATE TRIGGER trg_feedback_before_insert
BEFORE INSERT ON feedback
FOR EACH ROW
BEGIN
    DECLARE v_student INT;
    DECLARE v_status  VARCHAR(20);

    SELECT student_id, status INTO v_student, v_status
    FROM orders WHERE order_id = NEW.order_id;

    IF v_student IS NULL OR v_student <> NEW.student_id THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'This is not your order';
    END IF;
    IF v_status <> 'Completed' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'You can rate an order only after you receive it';
    END IF;
END//

-- ---------------------------------------------------------------------
-- PART 4: STORED PROCEDURES
-- ---------------------------------------------------------------------

-- sp_checkout: TRANSACTION in SQL. Pays a Pending order from the wallet.
-- Either everything succeeds (wallet deducted + order marked Placed) or ROLLBACK.
CREATE PROCEDURE sp_checkout(IN p_order_id INT)
BEGIN
    DECLARE v_student INT;
    DECLARE v_total   DECIMAL(10,2);
    DECLARE v_status  VARCHAR(20);
    DECLARE v_token   INT;
    DECLARE v_balance DECIMAL(10,2);

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    SELECT student_id, total_amount, status, daily_no
      INTO v_student, v_total, v_status, v_token
    FROM orders WHERE order_id = p_order_id FOR UPDATE;

    IF v_status <> 'Pending' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Order is not pending';
    END IF;

    SELECT wallet_balance INTO v_balance
    FROM students WHERE student_id = v_student FOR UPDATE;

    IF v_balance < v_total THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Insufficient wallet balance';
    END IF;

    SET @wallet_type = 'PAYMENT';
    SET @wallet_order_id = p_order_id;
    SET @wallet_note = CONCAT('Payment for token #', v_token);
    UPDATE students SET wallet_balance = wallet_balance - v_total
    WHERE student_id = v_student;
    SET @wallet_type = NULL;
    SET @wallet_order_id = NULL;
    SET @wallet_note = NULL;

    UPDATE orders SET status = 'Placed' WHERE order_id = p_order_id;

    INSERT INTO audit_log(table_name, action, description)
    VALUES ('orders', 'PAID', CONCAT('Token #', v_token, ' (order ', p_order_id, ') paid: Rs ', v_total));

    COMMIT;
END//

-- sp_daily_rollover: DAILY ARCHIVE into the audit log.
-- For every day before today that has orders but no summary yet, it writes one
-- DAILY_SUMMARY row (dated that day) into audit_log. Safe to run any number of times.
-- The server runs it at start-up and every midnight. Token numbers restart
-- from 1 automatically because daily_counter has a new row for each new date.
CREATE PROCEDURE sp_daily_rollover()
BEGIN
    INSERT INTO audit_log(table_name, action, description, logged_at)
    SELECT 'orders', 'DAILY_SUMMARY',
           CONCAT(d.order_day, ': ', d.total_orders, ' orders (last token #', d.last_token, '), ',
                  d.completed, ' completed, ', d.cancelled, ' cancelled, sales Rs ',
                  d.sales, ', refunds Rs ', d.refunds),
           CONCAT(d.order_day, ' 23:59:59')
    FROM (
        SELECT DATE(order_date) AS order_day,
               COUNT(*)         AS total_orders,
               MAX(daily_no)    AS last_token,
               SUM(status = 'Completed') AS completed,
               SUM(status = 'Cancelled') AS cancelled,
               COALESCE(SUM(CASE WHEN status IN ('Placed','Preparing','Ready','Completed')
                                 THEN total_amount ELSE 0 END), 0) AS sales,
               COALESCE(SUM(refund_amount), 0) AS refunds
        FROM orders
        WHERE DATE(order_date) < CURDATE() AND status <> 'Pending'
        GROUP BY DATE(order_date)
    ) d
    WHERE NOT EXISTS (
        SELECT 1 FROM audit_log a
        WHERE a.action = 'DAILY_SUMMARY'
          AND a.logged_at >= d.order_day
          AND a.logged_at <  DATE_ADD(d.order_day, INTERVAL 1 DAY)
    );
END//

DELIMITER ;

-- ---------------------------------------------------------------------
-- PART 5: SAMPLE DATA
-- ---------------------------------------------------------------------
INSERT INTO departments(dept_name) VALUES ('CSE'),('ECE'),('MECH'),('CIVIL'),('IT'),('EEE');

INSERT INTO colleges(college_name) VALUES
('Loyola-ICAM College of Engineering and Technology (LICET)');

-- All three demo students use the password: demo123
INSERT INTO students(mobile, name, college_id, dept_id, roll_no, password_hash, wallet_balance) VALUES
('9876543210', 'Arun Kumar', 1, 1, '21CS001', 'pbkdf2$100000$W8FOF8IlnHm7U88mVD+53Q==$zBr0B3DpZft9RsvFOTltdxYKgO+DH8ctK+GFO+TEw5c=', 500.00),
('9876501234', 'Priya S',    1, 2, '21EC014', 'pbkdf2$100000$etAdCDeDRO2Pa91G0ZQV+g==$7dPr+5TW7CwQ8AQmbzaI8jSSpq0+x5NXVoNyI1pCWxc=', 300.00),
('9812345678', 'Karthik R',  1, 3, '21ME007', 'pbkdf2$100000$cNmhah+2AmoiFT+mFTASaA==$Vebeq11sKogDtnHpHXW7/uOQeM6qe40R6YaohwCnLho=', 150.00);

INSERT INTO wallet_transactions(student_id, txn_type, amount, balance_after, description) VALUES
(1, 'TOPUP', 500.00, 500.00, 'Opening balance'),
(2, 'TOPUP', 300.00, 300.00, 'Opening balance'),
(3, 'TOPUP', 150.00, 150.00, 'Opening balance');

INSERT INTO categories(category_name) VALUES ('Meals'),('Snacks'),('Beverages');

INSERT INTO menu_items(item_name, category_id, price, stock) VALUES
('Veg Meals',1,60.00,50),
('Chicken Biryani',1,100.00,30),
('Samosa',2,15.00,100),
('Veg Puff',2,20.00,60),
('Tea',3,10.00,200),
('Cold Coffee',3,40.00,40);

-- Add-on price list (staff can change these on the staff page)
INSERT INTO customizations(custom_name, extra_price) VALUES
('Less spicy',       0.00),
('Extra spicy',      5.00),
('No onion',         0.00),
('Extra onion',      5.00),
('Extra mayonnaise', 10.00),
('Extra cheese',     15.00);

INSERT INTO staff(staff_code, name, role, password_hash) VALUES
('STF001', 'Ramesh Kumar', 'Manager', SHA2('admin123', 256)),
('STF002', 'Lakshmi S',    'Cook',    SHA2('kitchen456', 256));

-- ---------------------------------------------------------------------
-- PART 6: DEMO QUERIES (for your viva; run them one block at a time)
-- ---------------------------------------------------------------------
-- Order with an add-on (triggers: price copy, stock, token number, total):
--   INSERT INTO orders(student_id) VALUES (1);
--   SET @oid = LAST_INSERT_ID();
--   INSERT INTO order_items(order_id, item_id, quantity) VALUES (@oid, 1, 2);
--   INSERT INTO order_customizations(order_id, custom_id) VALUES (@oid, 5);   -- Extra mayonnaise
--   SELECT order_id, daily_no, total_amount FROM orders WHERE order_id = @oid; -- 120 + 10 = 130
--
-- Pay (transaction) and see the wallet statement written by the trigger:
--   CALL sp_checkout(@oid);
--   SELECT * FROM wallet_transactions WHERE student_id = 1 ORDER BY txn_id;
--
-- Refund rule: cancel within 5 minutes -> refund
--   UPDATE orders SET status = 'Cancelled', cancelled_by = 'Student' WHERE order_id = @oid;
--   SELECT status, refund_amount FROM orders WHERE order_id = @oid;
-- Refund rule: after 5 minutes -> no refund (pretend the order is 6 minutes old first)
--   UPDATE orders SET order_date = DATE_SUB(NOW(), INTERVAL 6 MINUTE) WHERE order_id = @oid2;
--   UPDATE orders SET status = 'Cancelled', cancelled_by = 'Student' WHERE order_id = @oid2;
--
-- Daily token numbers: each new day starts again from 1
--   SELECT * FROM daily_counter;
--
-- Daily audit summary for past days:
--   CALL sp_daily_rollover();
--   SELECT * FROM audit_log WHERE action = 'DAILY_SUMMARY';
--   SELECT DATE(logged_at) AS day, COUNT(*) AS entries FROM audit_log GROUP BY DATE(logged_at);
