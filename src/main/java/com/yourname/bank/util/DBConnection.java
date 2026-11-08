package com.yourname.bank.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens JDBC connections using settings from AppConfig. */
public final class DBConnection {
    private DBConnection() {}

    public static Connection getConnection() throws SQLException {
        String url  = AppConfig.get("db.url", "DB_URL", "jdbc:mysql://localhost:3306/bankdb");
        String user = AppConfig.get("db.user", "DB_USER", "root");
        String pass = AppConfig.get("db.password", "DB_PASSWORD", "");
        return DriverManager.getConnection(url, user, pass);
    }
}
