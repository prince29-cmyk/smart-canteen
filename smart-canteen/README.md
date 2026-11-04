# Smart Canteen System (DBMS Project)

Order-ahead canteen for students of ANY college.
**Backend:** Java (JDBC) | **Database:** MySQL 8 | **Frontend:** HTML / CSS / JavaScript

## Run it (4 steps)

1. **Database:** open `database/canteen.sql` in MySQL Workbench and run the whole file (lightning bolt).
   It drops and recreates `smart_canteen`, so old data is reset.
2. **Driver:** put `mysql-connector-j-x.x.x.jar` directly inside the `lib/` folder.
3. **Password:** in `src/Db.java` change `PASS` to your MySQL password (or set the environment variable `DB_PASS`).
4. **Start** (from the folder that contains `src`, `lib` and `web`):
   ```
   javac -d out src\Db.java src\Json.java src\Passwords.java src\Main.java
   java -cp "out;lib\mysql-connector-j-x.x.x.jar" Main
   ```
   Then open http://localhost:8080 (use `$env:PORT = "9090"` first if 8080 is busy).

Demo logins: students `9876543210`, `9876501234`, `9812345678` (password `demo123`).
Staff `STF001` / `admin123` (Manager) and `STF002` / `kitchen456` (Cook).

## Features

**Students**
- Register with a mobile number and password (any college). The login is saved on the device for 30 days.
- Order food, choose add-ons (less spicy, extra mayonnaise, no onion ...) whose extra price is fixed by staff, add a note.
- After ordering: "Token #5, you are #4 in the queue, your order will be delivered in about 5 minutes".
- Cancel within 5 minutes for a full refund. After 5 minutes the order can still be cancelled but there is no refund.
- Order history with a Reorder button, star rating and feedback for completed orders, a favourites list.
- Wallet statement: every top-up, payment and refund with the balance after each.

**Staff**
- Live orders (queue first, oldest on top): Start preparing, Mark ready, Handed over, Cancel and refund.
- Inventory (stock and price), add-on price table, feedback summary.
- Audit log arranged by day, with a daily summary line for every finished day.

**Every day:** token numbers restart from 1, and the previous day's orders are summarised into the audit log
(`sp_daily_rollover`, run at server start and every midnight). Orders stay in the database for history and wallet statements.

## DBMS concepts (for your viva)

| Concept | Where |
|---|---|
| Normalization (1NF to 3NF) | Explained at the top of `canteen.sql`. Tables: colleges, departments, students, categories, menu_items, customizations, orders, order_items, order_customizations, favourites, feedback, wallet_transactions, daily_counter, audit_log, staff |
| `trg_orders_daily_no` | BEFORE INSERT on orders: token number restarting from 1 each day |
| `trg_order_item_before_insert` / `after_insert` | Check stock, copy price, reduce stock, update total |
| `trg_custom_before_insert` / `after_insert` | Copy the add-on price fixed by staff, add it to the total |
| `trg_menu_before_update` | Stock 0 means unavailable, restock means available |
| `trg_menu_price_audit`, `trg_custom_price_audit` | Price changes written to the audit log |
| `trg_order_before_update` | Refund rule: full refund within 5 minutes, none after (staff cancellations always refunded) |
| `trg_order_cancel` | Restore stock, refund the wallet, audit entry |
| `trg_wallet_log` | Every wallet change is written to `wallet_transactions` (the statement) |
| `trg_feedback_before_insert` | Only the owner of a Completed order can rate it |
| Transaction (Java) | `Main.placeOrder()`: order + items + add-ons + payment commit or roll back together |
| Transaction (SQL) | `sp_checkout` with START TRANSACTION, COMMIT, ROLLBACK |
| Daily archive | `sp_daily_rollover` writes DAILY_SUMMARY rows into audit_log |

More demo queries are at the bottom of `canteen.sql`.

## Settings you may want to change

- Refund time (5 minutes): `REFUND_WINDOW_SECONDS` in `Main.java` AND the number `300` in trigger `trg_order_before_update`.
- Delivery message (5 minutes): `DELIVERY_MINUTES` in `Main.java`.
- Login saved for: `SESSION_DAYS` in `Main.java`.

## Notes

- Wallet top-up is simulated (no real payment). A real payment gateway would be needed for real use.
- Student passwords are stored as salted PBKDF2 hashes, never as plain text. 5 wrong passwords lock a login for 5 minutes.
- Staff and student pages are protected on the server, not only hidden in the browser.

## Troubleshooting

- **MySQL driver not found:** the jar is not directly inside `lib/`, or you started it with the VS Code Run button. Use the terminal command above.
- **Cannot connect to MySQL:** wrong password in `Db.java`, MySQL not running, or `canteen.sql` not run yet.
- **Address already in use:** an old copy is still running. Stop it, or use another port.
- **Unknown column ... in field list:** the database is from an older version. Run the new `canteen.sql` again.
