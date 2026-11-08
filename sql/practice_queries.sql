-- =====================================================================
-- Practice queries for the interview. Run after schema.sql and some app use.
-- mysql -u root -p bankdb < sql/practice_queries.sql
-- =====================================================================
USE bankdb;

-- 1. Basic filtering and sorting
SELECT account_no, holder_name, balance
FROM accounts
WHERE account_type = 'SAVINGS' AND balance > 1000
ORDER BY balance DESC;

-- 2. Aggregates with GROUP BY and HAVING (types with more than 1 transaction)
SELECT txn_type, COUNT(*) AS count, SUM(amount) AS total
FROM transactions
GROUP BY txn_type
HAVING COUNT(*) > 1
ORDER BY total DESC;

-- 3. INNER JOIN: every transaction with the holder's name
SELECT a.holder_name, t.txn_type, t.amount, t.txn_time
FROM transactions t
JOIN accounts a ON a.account_no = t.account_no
ORDER BY t.txn_time DESC;

-- 4. LEFT JOIN: accounts that never made a transaction
SELECT a.account_no, a.holder_name
FROM accounts a
LEFT JOIN transactions t ON t.account_no = a.account_no
WHERE t.txn_id IS NULL;

-- 5. Subquery: accounts with a balance above the average
SELECT account_no, holder_name, balance
FROM accounts
WHERE balance > (SELECT AVG(balance) FROM accounts);

-- 6. Second highest balance (classic interview question)
SELECT MAX(balance) AS second_highest
FROM accounts
WHERE balance < (SELECT MAX(balance) FROM accounts);

-- 7. Window function: running balance per account
SELECT account_no, txn_time, txn_type, amount,
       SUM(CASE WHEN txn_type IN ('DEPOSIT','TRANSFER_IN','INTEREST','LOAN_DISBURSAL')
                THEN amount ELSE -amount END)
           OVER (PARTITION BY account_no ORDER BY txn_id) AS running_net_flow
FROM transactions;

-- 8. Window function: rank accounts by balance within each account type
SELECT account_type, holder_name, balance,
       RANK() OVER (PARTITION BY account_type ORDER BY balance DESC) AS rank_in_type
FROM accounts;

-- 9. Loans together with the owner (uses a view)
SELECT * FROM v_loan_overview WHERE status = 'ACTIVE';

-- 10. Account summary (uses a view)
SELECT * FROM v_account_summary ORDER BY total_in DESC;

-- 11. Stored procedure call
CALL sp_statement(1001, '2020-01-01', CURDATE());

-- 12. Audit trail produced by the trigger
SELECT * FROM audit_log ORDER BY log_id DESC LIMIT 10;

-- 13. EXPLAIN: show that the index on (account_no, txn_time) is used
EXPLAIN SELECT * FROM transactions
WHERE account_no = 1001 AND txn_time >= '2026-01-01';

-- 14. Transaction demo (what the Java code does with JDBC): all-or-nothing transfer
START TRANSACTION;
UPDATE accounts SET balance = balance - 100 WHERE account_no = 1001;
UPDATE accounts SET balance = balance + 100 WHERE account_no = 1002;
ROLLBACK;   -- change to COMMIT to keep it
