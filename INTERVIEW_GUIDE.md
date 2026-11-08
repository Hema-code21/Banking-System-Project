# Interview Guide: Banking Transaction System

Read this tonight, then open the code and find each thing mentioned. Interviewers
can tell instantly if you only memorised answers, so understand the "why" behind each one.

## 1. Your 60-second pitch (practise saying it out loud)

"I built a banking system in Core Java with MySQL. It has savings and current accounts,
transfers, loans with EMI, monthly interest and an admin role. The main challenges I solved
were concurrency and data consistency. Transfers are thread-safe and deadlock-free because
I lock accounts in a fixed order, and every money operation is saved to the database in a
single JDBC transaction, so a transfer either fully happens or not at all. I also used SQL
views, a stored procedure and a trigger for reporting and auditing, and I wrote 28 JUnit tests."

## 2. Five-minute live demo script

1. Start `Main`. Point out "Connected to MySQL".
2. Create two accounts (one Savings, one Current).
3. Log in, deposit, transfer to the other account, show the mini statement.
4. Apply for a loan (show EMI), repay one EMI.
5. Enter a wrong PIN 3 times (lockout).
6. Admin login, freeze an account, then show that its transfer is rejected.
7. Admin SQL reports: balance by type, top accounts, audit log (mention it is filled by a trigger).
8. Main menu option 4: concurrency demo (5000 transfers, 50 threads, total stays the same).
9. Close and restart the app: the data is still there.

## 3. What to read tonight (in this order)

| File | Know this |
|---|---|
| `model/Account.java` | abstract class, `ReentrantLock`, prepare/commit split, why `BigDecimal` |
| `model/SavingsAccount`, `CurrentAccount` | polymorphism: different minimum allowed balance |
| `service/BankService.java` | the lock -> prepare -> save to DB -> commit pattern; ordered locking in `transfer` |
| `repository/JdbcAccountRepository.java` | `inTransaction` helper, `setAutoCommit(false)`, commit/rollback, `PreparedStatement` |
| `sql/schema.sql` | constraints, indexes, views, trigger, stored procedure |
| `model/Loan.java` | EMI formula |
| `service/InterestScheduler.java` | `ScheduledExecutorService`, daemon thread |
| `BankFeaturesTest.java` | tests that prove DB failure leaves memory unchanged |

## 4. Likely questions and good answers

**Java / OOP**
- *Where did you use abstraction and polymorphism?* `Account` is abstract with `minimumAllowedBalance()`. Savings returns 500, Current returns -5000 (overdraft). `withdraw` works the same for both and each type decides the limit.
- *Why custom exceptions?* They make business errors explicit (`InsufficientBalanceException`, `AccountFrozenException`) and are checked, so callers must handle them.
- *Why `BigDecimal` and not `double`?* `double` cannot represent 0.1 exactly, so money would drift. `BigDecimal` is exact, and I keep scale 2 to match `DECIMAL(15,2)`.
- *Why `ConcurrentHashMap`?* Many threads read and add accounts at once. `HashMap` is not thread-safe.
- *Why a `record` for `Transaction`?* It is immutable, so it is safe to share between threads.
- *Why an interface for the repository?* The service does not depend on JDBC, so tests run without a database, and I could switch to another database.

**Concurrency**
- *How do you prevent deadlock in a transfer?* Always lock the account with the smaller number first. If A->B and B->A run together, both threads lock the same account first, so a circular wait is impossible.
- *Why `ReentrantLock` and not `synchronized`?* One lock per account object, and it is re-entrant so `withdraw` can lock again inside `transfer`. It also gives `tryLock` and timeouts if needed.
- *How do you know it works?* The demo runs 5000 random transfers on 50 threads and checks the total money is unchanged. A JUnit test also runs opposite transfers and fails on timeout if there is a deadlock.
- *Why a daemon thread for interest?* So it never keeps the program from exiting. I also catch exceptions inside the task, because an uncaught exception silently cancels future runs of a scheduled task.

**JDBC / database**
- *Explain your transaction handling.* `setAutoCommit(false)`, run all updates, `commit()`. On any exception, `rollback()`. A transfer updates two balances and inserts two ledger rows in one transaction.
- *What if the DB save fails?* I save to the DB first and only then change memory, so memory and DB cannot disagree. A test proves it.
- *`PreparedStatement` vs `Statement`?* Prevents SQL injection and the database can reuse the query plan.
- *What is ACID?* Atomicity (all or nothing), Consistency (constraints hold), Isolation (concurrent transactions do not see each other's partial work), Durability (committed data survives a crash).

**SQL (all in `sql/schema.sql` and `sql/practice_queries.sql`)**
- *What is a view and why did you use one?* A saved query. `v_account_summary` joins accounts and transactions and totals money in and out, so reports stay simple.
- *What is a trigger?* Code the database runs automatically on an event. `trg_accounts_audit` writes to `audit_log` whenever a balance or frozen flag changes, even if someone edits the table directly.
- *What is a stored procedure?* Saved SQL logic stored in the database. `sp_statement(account, from, to)` is called from Java with `CallableStatement`.
- *Why those indexes?* `(account_no, txn_time)` speeds up statement queries. On a tiny table the optimizer may still scan, which is normal.
- *Primary key vs foreign key?* PK uniquely identifies a row. FK links `transactions.account_no` to `accounts`, so a transaction cannot exist without an account.
- *INNER vs LEFT JOIN?* INNER returns only matching rows. LEFT keeps all rows from the left table. I used LEFT JOIN in the view so accounts with no transactions still appear.
- *WHERE vs HAVING?* WHERE filters rows before grouping, HAVING filters groups after.
- *Second highest balance?* See query 6 in `practice_queries.sql`.
- *What do CHECK constraints do for you?* The database itself rejects negative amounts or unknown account types, as a safety net behind the Java validation.

**Design / honesty questions**
- *What are the limitations?* Locks work only inside one JVM. Several app instances on one database would need `SELECT ... FOR UPDATE` or optimistic locking. It is a console app with no REST API. PIN hashing is salted SHA-256, which is fine for learning; production would use bcrypt or Argon2.
- *What would you do next?* Spring Boot REST API, HikariCP connection pooling, JWT authentication, Docker, integration tests with Testcontainers.
- *What was the hardest part?* Pick a real one: deadlock-safe transfers, or keeping memory and database consistent when a save fails.

## 5. Resume bullets (edit to match what you can explain)

- Built a multithreaded banking system in Core Java and MySQL (JDBC) supporting savings/current accounts, transfers, EMI-based loans, interest scheduling and admin controls.
- Designed deadlock-free concurrent transfers using ordered `ReentrantLock` locking; verified with a 5000-transfer, 50-thread test that conserves total balance.
- Implemented atomic JDBC transactions (commit/rollback) and a repository layer, keeping memory and database consistent on failures.
- Used SQL views, a stored procedure, triggers, constraints and indexes for reporting and auditing; wrote 28 JUnit tests.

## 6. Be honest

If asked how you built it, say you used guidance and tools while learning, and that you can walk
through every part. That is credible. Claiming you wrote everything alone and then failing to
explain a method is the thing that actually hurts. If you do not know an answer, say what you
would check or try rather than guessing.
