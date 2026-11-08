-- =====================================================================
-- Banking system schema.  WARNING: drops and recreates everything.
-- Run:  mysql -u root -p < sql/schema.sql
-- SQL features used: PRIMARY/FOREIGN KEY, CHECK, DEFAULT, INDEX,
--                    VIEW, TRIGGER, STORED PROCEDURE, DECIMAL for money
-- =====================================================================
CREATE DATABASE IF NOT EXISTS bankdb;
USE bankdb;

DROP VIEW IF EXISTS v_account_summary;
DROP VIEW IF EXISTS v_loan_overview;
DROP PROCEDURE IF EXISTS sp_statement;
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS loans;
DROP TABLE IF EXISTS transactions;
DROP TABLE IF EXISTS accounts;

-- ---------- tables ----------
CREATE TABLE accounts (
    account_no   BIGINT AUTO_INCREMENT PRIMARY KEY,
    holder_name  VARCHAR(100)  NOT NULL,
    account_type VARCHAR(10)   NOT NULL,
    pin_salt     VARCHAR(64)   NOT NULL,
    pin_hash     VARCHAR(64)   NOT NULL,
    balance      DECIMAL(15,2) NOT NULL,
    is_frozen    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_account_type CHECK (account_type IN ('SAVINGS', 'CURRENT')),
    CONSTRAINT chk_min_balance  CHECK (balance >= -5000),
    INDEX idx_holder_name (holder_name)
) AUTO_INCREMENT = 1001;

CREATE TABLE transactions (
    txn_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no    BIGINT        NOT NULL,
    txn_type      VARCHAR(20)   NOT NULL,
    amount        DECIMAL(15,2) NOT NULL,
    balance_after DECIMAL(15,2) NOT NULL,
    txn_time      DATETIME(3)   NOT NULL,
    CONSTRAINT fk_txn_account FOREIGN KEY (account_no) REFERENCES accounts(account_no),
    CONSTRAINT chk_txn_amount CHECK (amount > 0),
    INDEX idx_txn_account_time (account_no, txn_time),
    INDEX idx_txn_type (txn_type)
);

CREATE TABLE loans (
    loan_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no   BIGINT        NOT NULL,
    principal    DECIMAL(15,2) NOT NULL,
    annual_rate  DECIMAL(5,2)  NOT NULL,
    months       INT           NOT NULL,
    emi          DECIMAL(15,2) NOT NULL,
    outstanding  DECIMAL(15,2) NOT NULL,
    status       VARCHAR(10)   NOT NULL,
    start_date   DATE          NOT NULL,
    CONSTRAINT fk_loan_account FOREIGN KEY (account_no) REFERENCES accounts(account_no),
    CONSTRAINT chk_loan_principal CHECK (principal > 0),
    CONSTRAINT chk_loan_status CHECK (status IN ('ACTIVE', 'CLOSED')),
    INDEX idx_loan_account (account_no)
) AUTO_INCREMENT = 5001;

CREATE TABLE audit_log (
    log_id     BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_no BIGINT       NOT NULL,
    action     VARCHAR(20)  NOT NULL,
    old_value  VARCHAR(30),
    new_value  VARCHAR(30),
    changed_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

-- ---------- views ----------
CREATE VIEW v_account_summary AS
SELECT a.account_no, a.holder_name, a.account_type, a.balance, a.is_frozen,
       COUNT(t.txn_id) AS txn_count,
       COALESCE(SUM(CASE WHEN t.txn_type IN ('DEPOSIT','TRANSFER_IN','INTEREST','LOAN_DISBURSAL')
                         THEN t.amount END), 0) AS total_in,
       COALESCE(SUM(CASE WHEN t.txn_type IN ('WITHDRAW','TRANSFER_OUT','LOAN_REPAYMENT')
                         THEN t.amount END), 0) AS total_out,
       MAX(t.txn_time) AS last_activity
FROM accounts a
LEFT JOIN transactions t ON t.account_no = a.account_no
GROUP BY a.account_no, a.holder_name, a.account_type, a.balance, a.is_frozen;

CREATE VIEW v_loan_overview AS
SELECT l.loan_id, a.holder_name, l.account_no, l.principal, l.emi, l.outstanding, l.status
FROM loans l
JOIN accounts a ON a.account_no = l.account_no;

-- ---------- stored procedure and trigger (need a custom delimiter) ----------
DELIMITER //

CREATE PROCEDURE sp_statement(IN p_account BIGINT, IN p_from DATE, IN p_to DATE)
BEGIN
    SELECT txn_time, txn_type, amount, balance_after
    FROM transactions
    WHERE account_no = p_account
      AND DATE(txn_time) BETWEEN p_from AND p_to
    ORDER BY txn_id;
END//

-- Automatically records every balance or freeze change, even if changed outside the Java app.
CREATE TRIGGER trg_accounts_audit
AFTER UPDATE ON accounts
FOR EACH ROW
BEGIN
    IF OLD.balance <> NEW.balance THEN
        INSERT INTO audit_log (account_no, action, old_value, new_value)
        VALUES (NEW.account_no, 'BALANCE_CHANGE', OLD.balance, NEW.balance);
    END IF;
    IF OLD.is_frozen <> NEW.is_frozen THEN
        INSERT INTO audit_log (account_no, action, old_value, new_value)
        VALUES (NEW.account_no, IF(NEW.is_frozen, 'FROZEN', 'UNFROZEN'), OLD.is_frozen, NEW.is_frozen);
    END IF;
END//

DELIMITER ;
