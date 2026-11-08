package com.yourname.bank.repository;

import com.yourname.bank.exception.PersistenceException;
import com.yourname.bank.util.DBConnection;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reports that use SQL features directly in the database:
 * GROUP BY / aggregates, JOINs, VIEWs, a STORED PROCEDURE, LIKE and ORDER BY ... LIMIT.
 * Each method returns printable text lines (header + rows).
 */
public class JdbcReportRepository {

    /** GROUP BY with SUM / COUNT / AVG. */
    public List<String> balanceByAccountType() throws PersistenceException {
        return query("SELECT account_type, COUNT(*) AS accounts, SUM(balance) AS total_balance, "
                   + "ROUND(AVG(balance), 2) AS avg_balance "
                   + "FROM accounts GROUP BY account_type ORDER BY total_balance DESC");
    }

    /** Uses the v_account_summary VIEW (accounts LEFT JOIN transactions, grouped). */
    public List<String> topAccounts(int n) throws PersistenceException {
        return query("SELECT account_no, holder_name, balance, txn_count, total_in, total_out "
                   + "FROM v_account_summary ORDER BY balance DESC LIMIT ?", n);
    }

    /** GROUP BY on a date function. */
    public List<String> dailyTransactionSummary() throws PersistenceException {
        return query("SELECT DATE(txn_time) AS day, COUNT(*) AS txns, SUM(amount) AS total_amount "
                   + "FROM transactions GROUP BY DATE(txn_time) ORDER BY day DESC LIMIT 7");
    }

    /** Uses the v_loan_overview VIEW (loans JOIN accounts). */
    public List<String> loanOverview() throws PersistenceException {
        return query("SELECT loan_id, holder_name, principal, emi, outstanding, status "
                   + "FROM v_loan_overview ORDER BY loan_id");
    }

    /** Rows written automatically by the trigger on the accounts table. */
    public List<String> auditLog(int n) throws PersistenceException {
        return query("SELECT log_id, account_no, action, old_value, new_value, changed_at "
                   + "FROM audit_log ORDER BY log_id DESC LIMIT ?", n);
    }

    /** LIKE search. */
    public List<String> searchHolders(String keyword) throws PersistenceException {
        return query("SELECT account_no, holder_name, account_type, balance FROM accounts "
                   + "WHERE holder_name LIKE ? ORDER BY holder_name", "%" + keyword + "%");
    }

    /** Calls the stored procedure sp_statement(account, from, to). */
    public List<String> statementBetween(long accountNo, LocalDate from, LocalDate to) throws PersistenceException {
        try (Connection con = DBConnection.getConnection();
             CallableStatement cs = con.prepareCall("{call sp_statement(?, ?, ?)}")) {
            cs.setLong(1, accountNo);
            cs.setDate(2, Date.valueOf(from));
            cs.setDate(3, Date.valueOf(to));
            try (ResultSet rs = cs.executeQuery()) {
                return format(rs);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Report failed: " + e.getMessage(), e);
        }
    }

    // ---------- helpers ----------
    private List<String> query(String sql, Object... params) throws PersistenceException {
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return format(rs);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Report failed: " + e.getMessage(), e);
        }
    }

    private List<String> format(ResultSet rs) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        List<String> lines = new ArrayList<>();

        StringBuilder header = new StringBuilder();
        for (int i = 1; i <= cols; i++) header.append(i > 1 ? " | " : "").append(md.getColumnLabel(i));
        lines.add(header.toString());

        int rows = 0;
        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= cols; i++) row.append(i > 1 ? " | " : "").append(rs.getString(i));
            lines.add(row.toString());
            rows++;
        }
        if (rows == 0) lines.add("(no rows)");
        return lines;
    }
}
