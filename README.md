# Banking Transaction System (Core Java + MySQL)

A console banking system built with Core Java. Demonstrates OOP, collections, custom exceptions,
multithreading, JDBC transactions with persistence, SQL views/triggers/procedures, and the Streams API.

## Features
**Customer**
- Savings and Current accounts (inheritance + polymorphism; minimum balance vs overdraft)
- Deposit, withdraw, transfer, balance, mini statement, filter by amount / date / type, spending summary, CSV export
- Loans with EMI calculation and repayment (10% p.a., 6 to 60 months)
- Change PIN; salted SHA-256 PIN hashing; login locks for 60 seconds after 3 wrong PINs
- Statement between dates via a SQL stored procedure

**Admin** (password in `db.properties`, default `admin123`)
- List accounts, freeze / unfreeze, total bank balance, credit interest now
- SQL reports: balance by type (GROUP BY), top accounts (VIEW), daily summary, loan overview (VIEW + JOIN), audit log (TRIGGER), search by name (LIKE)

**Engineering**
- Thread-safe, deadlock-free transfers (`ReentrantLock`, locks taken in account-number order)
- Monthly interest credited by a background `ScheduledExecutorService`
- MySQL persistence; each operation is one atomic DB transaction (commit / rollback)
- Falls back to in-memory mode if MySQL is unreachable
- Concurrency demo: 50 threads, 5000 transfers, total money conserved
- 28 JUnit 5 tests

## Tech stack
Java 25 (LTS), Maven, MySQL, JDBC, JUnit 5

## Architecture
```
Main (console UI)
  -> BankService (business rules, locking)         InterestScheduler (background thread)
       -> AccountRepository (interface)
            |- JdbcAccountRepository   (MySQL, JDBC transactions)
            |- InMemoryAccountRepository (tests / fallback)
  -> JdbcReportRepository (views, stored procedure, GROUP BY, LIKE)
model: Account (abstract) <- SavingsAccount, CurrentAccount ; Transaction ; Loan ; AccountType
```
Every money operation follows: lock -> validate -> save to DB -> update memory.
If the DB save fails, memory is untouched, so memory and database never disagree.

## Database
`sql/schema.sql` creates: 4 tables with primary/foreign keys, CHECK constraints and indexes;
2 views (`v_account_summary`, `v_loan_overview`); stored procedure `sp_statement`;
trigger `trg_accounts_audit`. `sql/practice_queries.sql` has joins, subqueries, window functions and more.

## Setup
1. Install MySQL and run `sql/schema.sql` (it drops and recreates the tables).
2. Copy `db.properties.example` to `db.properties` and enter your MySQL user and password.
3. Run `Main.java` (VS Code: click Run above `main`) or:
```
mvn test
mvn compile exec:java -Dexec.mainClass=com.yourname.bank.Main
```
On start you should see: `Connected to MySQL. Loaded N saved account(s).`

## Design decisions
- **Repository interface:** the service does not know about JDBC, so tests need no database.
- **ReentrantLock per account:** re-entrant, so deposit/withdraw can lock again inside a transfer.
- **Deadlock prevention:** always lock the lower account number first.
- **BigDecimal with scale 2:** no floating-point errors; matches `DECIMAL(15,2)`.
- **PreparedStatement:** prevents SQL injection.
- **Limitation:** locks are per JVM. Several app instances on one database would need row locks (`SELECT ... FOR UPDATE`).

## Future improvements
Spring Boot REST API, HikariCP connection pooling, JWT auth, Docker.
