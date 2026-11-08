package com.yourname.bank.repository;

import com.yourname.bank.exception.PersistenceException;
import com.yourname.bank.model.*;
import com.yourname.bank.util.DBConnection;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL implementation using JDBC. Run sql/schema.sql first.
 * Every method that changes more than one row uses ONE database transaction:
 * setAutoCommit(false) -> work -> commit(), or rollback() if anything fails.
 */
public class JdbcAccountRepository implements AccountRepository {

    @FunctionalInterface
    private interface DbWork<T> {
        T run(Connection con) throws SQLException;
    }

    /** Runs the work in a DB transaction; rolls back and wraps the error if anything fails. */
    private <T> T inTransaction(String what, DbWork<T> work) throws PersistenceException {
        try (Connection con = DBConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                T result = work.run(con);
                con.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not " + what + ": " + e.getMessage(), e);
        }
    }

    // ---------- accounts ----------
    @Override
    public long insertAccount(AccountType type, String holder, String pinSalt, String pinHash, BigDecimal opening)
            throws PersistenceException {
        return inTransaction("create account", con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO accounts (holder_name, account_type, pin_salt, pin_hash, balance) "
                  + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, holder);
                ps.setString(2, type.name());
                ps.setString(3, pinSalt);
                ps.setString(4, pinHash);
                ps.setBigDecimal(5, opening);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        });
    }

    @Override
    public void saveTransactions(List<Transaction> txns) throws PersistenceException {
        inTransaction("save transaction", con -> {
            for (Transaction t : txns) writeTxn(con, t);
            return null;
        });
    }

    /** Updates the account balance and appends one ledger row. */
    private void writeTxn(Connection con, Transaction t) throws SQLException {
        try (PreparedStatement upd = con.prepareStatement("UPDATE accounts SET balance = ? WHERE account_no = ?");
             PreparedStatement ins = con.prepareStatement(
                     "INSERT INTO transactions (account_no, txn_type, amount, balance_after, txn_time) "
                   + "VALUES (?, ?, ?, ?, ?)")) {
            upd.setBigDecimal(1, t.balanceAfter());
            upd.setLong(2, t.accountNumber());
            upd.executeUpdate();

            ins.setLong(1, t.accountNumber());
            ins.setString(2, t.type().name());
            ins.setBigDecimal(3, t.amount());
            ins.setBigDecimal(4, t.balanceAfter());
            ins.setTimestamp(5, Timestamp.valueOf(t.timestamp()));
            ins.executeUpdate();
        }
    }

    @Override
    public void updateFrozen(long accountNumber, boolean frozen) throws PersistenceException {
        inTransaction("update account status", con -> {
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET is_frozen = ? WHERE account_no = ?")) {
                ps.setBoolean(1, frozen);
                ps.setLong(2, accountNumber);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void updatePin(long accountNumber, String pinSalt, String pinHash) throws PersistenceException {
        inTransaction("change PIN", con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE accounts SET pin_salt = ?, pin_hash = ? WHERE account_no = ?")) {
                ps.setString(1, pinSalt);
                ps.setString(2, pinHash);
                ps.setLong(3, accountNumber);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---------- loans ----------
    @Override
    public long saveLoanDisbursal(Loan loan, Transaction credit) throws PersistenceException {
        return inTransaction("save loan", con -> {
            long id;
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO loans (account_no, principal, annual_rate, months, emi, outstanding, status, start_date) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, loan.getAccountNumber());
                ps.setBigDecimal(2, loan.getPrincipal());
                ps.setBigDecimal(3, loan.getAnnualRatePercent());
                ps.setInt(4, loan.getMonths());
                ps.setBigDecimal(5, loan.getEmi());
                ps.setBigDecimal(6, loan.getOutstanding());
                ps.setString(7, loan.getStatus().name());
                ps.setDate(8, Date.valueOf(loan.getStartDate()));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            writeTxn(con, credit);      // money credited in the SAME transaction as the loan row
            return id;
        });
    }

    @Override
    public void saveLoanRepayment(long loanId, BigDecimal newOutstanding, Loan.Status newStatus, Transaction debit)
            throws PersistenceException {
        inTransaction("save loan repayment", con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE loans SET outstanding = ?, status = ? WHERE loan_id = ?")) {
                ps.setBigDecimal(1, newOutstanding);
                ps.setString(2, newStatus.name());
                ps.setLong(3, loanId);
                ps.executeUpdate();
            }
            writeTxn(con, debit);
            return null;
        });
    }

    // ---------- loading at startup ----------
    @Override
    public List<Account> loadAll() throws PersistenceException {
        Map<Long, Account> byNumber = new LinkedHashMap<>();
        try (Connection con = DBConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT account_no, holder_name, account_type, pin_salt, pin_hash, balance, is_frozen "
                  + "FROM accounts ORDER BY account_no");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long no = rs.getLong("account_no");
                    String name = rs.getString("holder_name");
                    String salt = rs.getString("pin_salt");
                    String hash = rs.getString("pin_hash");
                    BigDecimal bal = rs.getBigDecimal("balance");
                    AccountType type = AccountType.valueOf(rs.getString("account_type"));
                    Account acc = (type == AccountType.SAVINGS)
                            ? new SavingsAccount(no, name, salt, hash, bal)
                            : new CurrentAccount(no, name, salt, hash, bal);
                    acc.setFrozen(rs.getBoolean("is_frozen"));
                    byNumber.put(no, acc);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT account_no, txn_type, amount, balance_after, txn_time "
                  + "FROM transactions ORDER BY txn_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Account acc = byNumber.get(rs.getLong("account_no"));
                    if (acc == null) continue;
                    acc.restoreTransaction(new Transaction(
                            acc.getAccountNumber(),
                            Transaction.Type.valueOf(rs.getString("txn_type")),
                            rs.getBigDecimal("amount"),
                            rs.getBigDecimal("balance_after"),
                            rs.getTimestamp("txn_time").toLocalDateTime()));
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load accounts: " + e.getMessage(), e);
        }
        return new ArrayList<>(byNumber.values());
    }

    @Override
    public List<Loan> loadLoans() throws PersistenceException {
        List<Loan> loans = new ArrayList<>();
        try (Connection con = DBConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT loan_id, account_no, principal, annual_rate, months, emi, outstanding, status, start_date "
                   + "FROM loans ORDER BY loan_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                loans.add(new Loan(rs.getLong("loan_id"), rs.getLong("account_no"),
                        rs.getBigDecimal("principal"), rs.getBigDecimal("annual_rate"),
                        rs.getInt("months"), rs.getBigDecimal("emi"), rs.getBigDecimal("outstanding"),
                        Loan.Status.valueOf(rs.getString("status")), rs.getDate("start_date").toLocalDate()));
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not load loans: " + e.getMessage(), e);
        }
        return loans;
    }
}
