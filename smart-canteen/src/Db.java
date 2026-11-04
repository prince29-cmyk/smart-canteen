import java.sql.*;
import java.util.*;

/** Small JDBC helper: connection settings + query/update shortcuts. */
public class Db {

    // ---- Change these (or set environment variables DB_URL / DB_USER / DB_PASS) ----
    static final String URL  = env("DB_URL",
            "jdbc:mysql://localhost:3306/smart_canteen?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Kolkata");
    static final String USER = env("DB_USER", "root");
    static final String PASS = env("DB_PASS", "root");
    // ---------------------------------------------------------------------------------

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /** Runs a SELECT and returns rows as a list of column-name -> value maps. */
    static List<Map<String, Object>> query(String sql, Object... params) throws SQLException {
        try (Connection c = get(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return toList(rs);
            }
        }
    }

    /** Runs INSERT / UPDATE / DELETE and returns the number of affected rows. */
    static int update(String sql, Object... params) throws SQLException {
        try (Connection c = get(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] == null) ps.setNull(i + 1, Types.NULL);
            else ps.setObject(i + 1, params[i]);
        }
    }

    /** Runs a stored procedure call such as CALL sp_daily_rollover(). */
    static void call(String sql) throws SQLException {
        try (Connection c = get(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    static List<Map<String, Object>> toList(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= cols; i++) {
                row.put(md.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }
}
