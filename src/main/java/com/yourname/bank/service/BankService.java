package com.yourname.bank.service;

import com.yourname.bank.exception.*;
import com.yourname.bank.model.*;
import com.yourname.bank.repository.AccountRepository;
import com.yourname.bank.repository.InMemoryAccountRepository;
import com.yourname.bank.util.PasswordUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * All business rules. Every money operation follows the same pattern:
 *   lock -> prepare (validate) -> SAVE TO DB -> commit to memory.
 * If the DB save fails, memory is never changed, so memory and database cannot disagree.
 */
public class BankService {
    private static final Logger LOG = Logger.getLogger(BankService.class.getName());

    // Loan rules
    public static final BigDecimal LOAN_RATE_PERCENT = new BigDecimal("10.00");
    public static final BigDecimal MIN_LOAN = new BigDecimal("1000");
    public static final BigDecimal MAX_LOAN = new BigDecimal("500000");
    public static final int MIN_MONTHS = 6;
    public static final int MAX_MONTHS = 60;

    // Login protection
    private static final int MAX_FAILED_LOGINS = 3;
    private static final long LOCK_MILLIS = 60_000;

    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();
    private final Map<Long, Loan> loans = new ConcurrentHashMap<>();
    private final Map<Long, LoginState> loginStates = new ConcurrentHashMap<>();
    private final AccountRepository repo;

    private static final class LoginState {
        int failures;
        long lockedUntil;
    }

    /** In-memory only (used by tests and the concurrency demo). */
    public BankService() { this(new InMemoryAccountRepository()); }

    public BankService(AccountRepository repo) { this.repo = repo; }

    /** Loads all saved accounts and loans from the repository. Call once at startup. */
    public int load() throws PersistenceException {
        for (Account a : repo.loadAll()) accounts.put(a.getAccountNumber(), a);
        for (Loan l : repo.loadLoans()) loans.put(l.getLoanId(), l);
        return accounts.size();
    }

    public int accountCount() { return accounts.size(); }

    // =========================== accounts ===========================
    public Account createAccount(AccountType type, String holder, String pin, BigDecimal opening)
            throws BankException {
        if (holder == null || holder.isBlank()) throw new InvalidAmountException("Holder name is required.");
        requireValidPin(pin);
        BigDecimal open = money(opening);
        if (open.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidAmountException("Opening balance cannot be negative.");
        }
        if (type == AccountType.SAVINGS && open.compareTo(SavingsAccount.MIN_BALANCE) < 0) {
            throw new InvalidAmountException("Savings account needs at least " + SavingsAccount.MIN_BALANCE);
        }
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash(pin, salt);
        long no = repo.insertAccount(type, holder.trim(), salt, hash, open);

        Account acc = (type == AccountType.SAVINGS)
                ? new SavingsAccount(no, holder.trim(), salt, hash, open)
                : new CurrentAccount(no, holder.trim(), salt, hash, open);
        accounts.put(no, acc);
        LOG.info("Created " + acc);
        return acc;
    }

    public Account getAccount(long accountNumber) throws AccountNotFoundException {
        Account acc = accounts.get(accountNumber);
        if (acc == null) throw new AccountNotFoundException(accountNumber);
        return acc;
    }

    /** Wrong PIN 3 times in a row locks login for 60 seconds. */
    public Account login(long accountNumber, String pin)
            throws AccountNotFoundException, AuthenticationException {
        Account acc = getAccount(accountNumber);
        LoginState st = loginStates.computeIfAbsent(accountNumber, k -> new LoginState());
        synchronized (st) {
            long now = System.currentTimeMillis();
            if (now < st.lockedUntil) {
                long secs = (st.lockedUntil - now + 999) / 1000;
                throw new AuthenticationException("Too many wrong attempts. Try again in " + secs + " seconds.");
            }
            if (!acc.verifyPin(pin)) {
                st.failures++;
                if (st.failures >= MAX_FAILED_LOGINS) {
                    st.failures = 0;
                    st.lockedUntil = now + LOCK_MILLIS;
                    throw new AuthenticationException("Wrong PIN. Login locked for 60 seconds.");
                }
                throw new AuthenticationException("Wrong PIN. Attempts left: " + (MAX_FAILED_LOGINS - st.failures));
            }
            st.failures = 0;
        }
        return acc;
    }

    public void changePin(long accNo, String oldPin, String newPin) throws BankException {
        Account acc = getAccount(accNo);
        if (!acc.verifyPin(oldPin)) throw new AuthenticationException("Current PIN is wrong.");
        requireValidPin(newPin);
        String salt = PasswordUtil.generateSalt();
        String hash = PasswordUtil.hash(newPin, salt);
        acc.getLock().lock();
        try {
            repo.updatePin(accNo, salt, hash);
            acc.setPin(salt, hash);
        } finally {
            acc.getLock().unlock();
        }
    }

    private void requireValidPin(String pin) throws AuthenticationException {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new AuthenticationException("PIN must be exactly 4 digits.");
        }
    }

    // =========================== money ===========================
    public void deposit(long accNo, BigDecimal amount) throws BankException {
        Account acc = getAccount(accNo);
        BigDecimal amt = money(amount);
        acc.getLock().lock();
        try {
            Transaction t = acc.prepareDeposit(amt, Transaction.Type.DEPOSIT);
            repo.saveTransactions(List.of(t));
            acc.commit(t);
        } finally {
            acc.getLock().unlock();
        }
    }

    public void withdraw(long accNo, BigDecimal amount) throws BankException {
        Account acc = getAccount(accNo);
        BigDecimal amt = money(amount);
        acc.getLock().lock();
        try {
            Transaction t = acc.prepareWithdraw(amt, Transaction.Type.WITHDRAW);
            repo.saveTransactions(List.of(t));
            acc.commit(t);
        } finally {
            acc.getLock().unlock();
        }
    }

    /**
     * Deadlock-safe transfer: always lock the account with the SMALLER number first.
     * Both sides are saved to the DB in ONE database transaction.
     */
    public void transfer(long fromNo, long toNo, BigDecimal amount) throws BankException {
        if (fromNo == toNo) throw new InvalidAmountException("Cannot transfer to the same account.");
        Account from = getAccount(fromNo);
        Account to = getAccount(toNo);
        BigDecimal amt = money(amount);

        Account first  = fromNo < toNo ? from : to;
        Account second = fromNo < toNo ? to : from;

        first.getLock().lock();
        try {
            second.getLock().lock();
            try {
                Transaction out = from.prepareWithdraw(amt, Transaction.Type.TRANSFER_OUT);
                Transaction in  = to.prepareDeposit(amt, Transaction.Type.TRANSFER_IN);
                repo.saveTransactions(List.of(out, in));   // atomic in the database
                from.commit(out);
                to.commit(in);
            } finally {
                second.getLock().unlock();
            }
        } finally {
            first.getLock().unlock();
        }
    }

    /** Money is stored with 2 decimal places, same as the DECIMAL(15,2) column. */
    private BigDecimal money(BigDecimal amount) throws InvalidAmountException {
        if (amount == null) throw new InvalidAmountException("Amount is required.");
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    // =========================== interest ===========================
    /**
     * Credits one month of interest to every savings account. Called by InterestScheduler
     * (a background thread) or by the admin. Returns how many accounts received interest.
     */
    public int applyMonthlyInterest() {
        int credited = 0;
        for (Account acc : accounts.values()) {
            if (!(acc instanceof SavingsAccount savings)) continue;
            acc.getLock().lock();
            try {
                BigDecimal interest = savings.monthlyInterest();
                if (interest.signum() <= 0) continue;
                Transaction t = acc.prepareDeposit(interest, Transaction.Type.INTEREST);
                repo.saveTransactions(List.of(t));
                acc.commit(t);
                credited++;
            } catch (BankException e) {
                LOG.warning("Interest skipped for " + acc.getAccountNumber() + ": " + e.getMessage());
            } finally {
                acc.getLock().unlock();
            }
        }
        LOG.info("Monthly interest credited to " + credited + " account(s).");
        return credited;
    }

    // =========================== loans ===========================
    public Loan applyForLoan(long accNo, BigDecimal principal, int months) throws BankException {
        BigDecimal p = money(principal);
        if (p.compareTo(MIN_LOAN) < 0 || p.compareTo(MAX_LOAN) > 0) {
            throw new LoanException("Loan amount must be between " + MIN_LOAN + " and " + MAX_LOAN + ".");
        }
        if (months < MIN_MONTHS || months > MAX_MONTHS) {
            throw new LoanException("Tenure must be between " + MIN_MONTHS + " and " + MAX_MONTHS + " months.");
        }
        Account acc = getAccount(accNo);
        acc.getLock().lock();
        try {
            boolean hasActive = loans.values().stream().anyMatch(
                    l -> l.getAccountNumber() == accNo && l.getStatus() == Loan.Status.ACTIVE);
            if (hasActive) throw new LoanException("You already have an active loan. Repay it first.");

            BigDecimal emi = Loan.calculateEmi(p, LOAN_RATE_PERCENT, months);
            Loan draft = new Loan(0, accNo, p, LOAN_RATE_PERCENT, months, emi,
                    Loan.totalPayable(emi, months), Loan.Status.ACTIVE, LocalDate.now());

            Transaction credit = acc.prepareDeposit(p, Transaction.Type.LOAN_DISBURSAL);
            long id = repo.saveLoanDisbursal(draft, credit);     // loan row + credit in one DB transaction
            acc.commit(credit);

            Loan loan = draft.withId(id);
            loans.put(id, loan);
            return loan;
        } finally {
            acc.getLock().unlock();
        }
    }

    /** Pays one EMI (or the remaining amount if smaller). Returns the amount paid. */
    public BigDecimal repayLoan(long accNo, long loanId) throws BankException {
        Loan loan = loans.get(loanId);
        if (loan == null || loan.getAccountNumber() != accNo) throw new LoanException("Loan not found: " + loanId);
        Account acc = getAccount(accNo);
        acc.getLock().lock();
        try {
            if (loan.getStatus() == Loan.Status.CLOSED) throw new LoanException("Loan is already fully repaid.");
            BigDecimal pay = loan.getEmi().min(loan.getOutstanding());
            Transaction debit = acc.prepareWithdraw(pay, Transaction.Type.LOAN_REPAYMENT);

            BigDecimal left = loan.getOutstanding().subtract(pay);
            Loan.Status newStatus = left.signum() <= 0 ? Loan.Status.CLOSED : Loan.Status.ACTIVE;
            repo.saveLoanRepayment(loanId, left.max(BigDecimal.ZERO), newStatus, debit);

            acc.commit(debit);
            loan.applyPayment(pay);
            return pay;
        } finally {
            acc.getLock().unlock();
        }
    }

    public List<Loan> loansOf(long accNo) {
        return loans.values().stream()
                .filter(l -> l.getAccountNumber() == accNo)
                .sorted(Comparator.comparingLong(Loan::getLoanId))
                .collect(Collectors.toList());
    }

    // =========================== admin ===========================
    public List<Account> allAccounts() {
        return accounts.values().stream()
                .sorted(Comparator.comparingLong(Account::getAccountNumber))
                .collect(Collectors.toList());
    }

    public void setFrozen(long accNo, boolean frozen) throws BankException {
        Account acc = getAccount(accNo);
        acc.getLock().lock();
        try {
            repo.updateFrozen(accNo, frozen);
            acc.setFrozen(frozen);
        } finally {
            acc.getLock().unlock();
        }
    }

    public BigDecimal totalBankBalance() {
        return accounts.values().stream()
                .map(Account::getBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // =========================== reports (Streams API) ===========================
    public List<Transaction> miniStatement(long accNo, int count) throws AccountNotFoundException {
        List<Transaction> all = getAccount(accNo).getHistory();
        return all.stream()
                .skip(Math.max(0, all.size() - count))
                .collect(Collectors.toList());
    }

    public List<Transaction> filterByMinAmount(long accNo, BigDecimal min) throws AccountNotFoundException {
        return getAccount(accNo).getHistory().stream()
                .filter(t -> t.amount().compareTo(min) >= 0)
                .collect(Collectors.toList());
    }

    public List<Transaction> filterByDate(long accNo, LocalDate date) throws AccountNotFoundException {
        return getAccount(accNo).getHistory().stream()
                .filter(t -> t.timestamp().toLocalDate().equals(date))
                .collect(Collectors.toList());
    }

    public List<Transaction> filterByType(long accNo, Transaction.Type type) throws AccountNotFoundException {
        return getAccount(accNo).getHistory().stream()
                .filter(t -> t.type() == type)
                .collect(Collectors.toList());
    }

    /** Total amount per transaction type, e.g. {DEPOSIT=500.00, WITHDRAW=120.00}. */
    public Map<Transaction.Type, BigDecimal> totalsByType(long accNo) throws AccountNotFoundException {
        return getAccount(accNo).getHistory().stream()
                .collect(Collectors.groupingBy(Transaction::type,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::amount, BigDecimal::add)));
    }

    public void exportStatementCsv(long accNo, Path file) throws AccountNotFoundException, IOException {
        List<Transaction> list = getAccount(accNo).getHistory();
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println("timestamp,type,amount,balanceAfter");
            for (Transaction t : list) {
                out.printf("%s,%s,%s,%s%n", t.timestamp().withNano(0), t.type(), t.amount(), t.balanceAfter());
            }
        }
    }
}
