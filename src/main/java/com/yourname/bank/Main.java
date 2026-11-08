package com.yourname.bank;

import com.yourname.bank.exception.BankException;
import com.yourname.bank.exception.PersistenceException;
import com.yourname.bank.model.Account;
import com.yourname.bank.model.AccountType;
import com.yourname.bank.model.Loan;
import com.yourname.bank.model.Transaction;
import com.yourname.bank.repository.JdbcAccountRepository;
import com.yourname.bank.repository.JdbcReportRepository;
import com.yourname.bank.service.BankService;
import com.yourname.bank.service.InterestScheduler;
import com.yourname.bank.util.AppConfig;
import com.yourname.bank.util.PasswordUtil;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/** Console UI. Run this class from VS Code (Run button above main). */
public class Main {
    @FunctionalInterface
    private interface ReportCall {
        List<String> run(JdbcReportRepository r) throws PersistenceException;
    }

    private static final Scanner sc = new Scanner(System.in);
    private static JdbcReportRepository reports;                 // null when MySQL is not available
    private static final BankService bank = connect();

    // admin password is kept only as a salted hash in memory
    private static final String ADMIN_SALT = PasswordUtil.generateSalt();
    private static final String ADMIN_HASH =
            PasswordUtil.hash(AppConfig.get("admin.password", "ADMIN_PASSWORD", "admin123"), ADMIN_SALT);

    /** Tries MySQL first; if it is not reachable, runs in memory so the app still works. */
    private static BankService connect() {
        try {
            BankService service = new BankService(new JdbcAccountRepository());
            int n = service.load();
            reports = new JdbcReportRepository();
            System.out.println("Connected to MySQL. Loaded " + n + " saved account(s).");
            return service;
        } catch (BankException e) {
            System.out.println("WARNING: MySQL not available (" + e.getMessage() + ").");
            System.out.println("Running in memory only: data will be lost when you exit.");
            return new BankService();
        }
    }

    public static void main(String[] args) {
        // Credits monthly interest in the background (first run after 30 days, then every 30 days).
        InterestScheduler scheduler = new InterestScheduler(bank);
        scheduler.start(30, 30, TimeUnit.DAYS);

        System.out.println("=== Banking Transaction System ===");
        while (true) {
            System.out.println("\n1. Create account\n2. Customer login\n3. Admin login\n4. Run concurrency demo\n0. Exit");
            switch (readInt("Choose: ")) {
                case 1 -> createAccount();
                case 2 -> login();
                case 3 -> adminLogin();
                case 4 -> ConcurrentTransferDemo.run();
                case 0 -> { scheduler.stop(); System.out.println("Goodbye!"); return; }
                default -> System.out.println("Invalid option.");
            }
        }
    }

    // ============================ customer ============================
    private static void createAccount() {
        try {
            System.out.print("Holder name: ");
            String name = sc.nextLine().trim();
            System.out.print("Set 4-digit PIN: ");
            String pin = sc.nextLine().trim();
            int t = readInt("Type (1=Savings, 2=Current): ");
            BigDecimal opening = readMoney("Opening balance: ");
            Account acc = bank.createAccount(t == 1 ? AccountType.SAVINGS : AccountType.CURRENT,
                    name, pin, opening);
            System.out.println("Account created! Your account number is " + acc.getAccountNumber());
        } catch (BankException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private static void login() {
        try {
            long no = readLong("Account number: ");
            System.out.print("PIN: ");
            String pin = sc.nextLine().trim();
            Account acc = bank.login(no, pin);
            System.out.println("Welcome, " + acc.getHolderName() + "!");
            accountMenu(acc);
        } catch (BankException e) {
            System.out.println("Login failed: " + e.getMessage());
        }
    }

    private static void accountMenu(Account acc) {
        long no = acc.getAccountNumber();
        while (true) {
            System.out.println("\n 1. Balance            2. Deposit          3. Withdraw"
                    + "\n 4. Transfer           5. Mini statement   6. Filter transactions"
                    + "\n 7. Spending summary   8. Apply for loan   9. Repay loan EMI"
                    + "\n10. My loans          11. Change PIN      12. Export CSV"
                    + "\n13. Statement between dates (SQL stored procedure)"
                    + "\n 0. Logout");
            int choice = readInt("Choose: ");
            try {
                switch (choice) {
                    case 1 -> System.out.println(acc);
                    case 2 -> { bank.deposit(no, readMoney("Amount: ")); System.out.println("Deposited."); }
                    case 3 -> { bank.withdraw(no, readMoney("Amount: ")); System.out.println("Withdrawn."); }
                    case 4 -> {
                        long to = readLong("Transfer to account: ");
                        bank.transfer(no, to, readMoney("Amount: "));
                        System.out.println("Transfer successful.");
                    }
                    case 5 -> bank.miniStatement(no, 5).forEach(System.out::println);
                    case 6 -> filterMenu(no);
                    case 7 -> bank.totalsByType(no).forEach((type, total) ->
                            System.out.printf("%-14s %10s%n", type, total));
                    case 8 -> applyLoan(no);
                    case 9 -> repayLoan(no);
                    case 10 -> {
                        List<Loan> list = bank.loansOf(no);
                        if (list.isEmpty()) System.out.println("No loans.");
                        list.forEach(System.out::println);
                    }
                    case 11 -> {
                        System.out.print("Current PIN: ");
                        String oldPin = sc.nextLine().trim();
                        System.out.print("New 4-digit PIN: ");
                        String newPin = sc.nextLine().trim();
                        bank.changePin(no, oldPin, newPin);
                        System.out.println("PIN changed.");
                    }
                    case 12 -> {
                        Path p = Path.of("statement_" + no + ".csv");
                        bank.exportStatementCsv(no, p);
                        System.out.println("Saved to " + p.toAbsolutePath());
                    }
                    case 13 -> {
                        LocalDate from = readDate("From date (yyyy-mm-dd): ");
                        LocalDate to = readDate("To date (yyyy-mm-dd): ");
                        showReport(r -> r.statementBetween(no, from, to));
                    }
                    case 0 -> { return; }
                    default -> System.out.println("Invalid option.");
                }
            } catch (BankException | IOException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void filterMenu(long no) throws BankException {
        int c = readInt("Filter by: 1=minimum amount, 2=date, 3=type: ");
        List<Transaction> result;
        if (c == 1) {
            result = bank.filterByMinAmount(no, readMoney("Minimum amount: "));
        } else if (c == 2) {
            result = bank.filterByDate(no, readDate("Date (yyyy-mm-dd): "));
        } else {
            System.out.println("Types: " + java.util.Arrays.toString(Transaction.Type.values()));
            System.out.print("Type: ");
            try {
                result = bank.filterByType(no, Transaction.Type.valueOf(sc.nextLine().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                System.out.println("Unknown type.");
                return;
            }
        }
        if (result.isEmpty()) System.out.println("No matching transactions.");
        result.forEach(System.out::println);
    }

    private static void applyLoan(long no) throws BankException {
        System.out.printf("Loan rate %s%% p.a. | amount %s to %s | tenure %d to %d months%n",
                BankService.LOAN_RATE_PERCENT, BankService.MIN_LOAN, BankService.MAX_LOAN,
                BankService.MIN_MONTHS, BankService.MAX_MONTHS);
        BigDecimal amount = readMoney("Loan amount: ");
        int months = readInt("Tenure in months: ");
        BigDecimal emi = Loan.calculateEmi(amount, BankService.LOAN_RATE_PERCENT, months);
        System.out.println("Your EMI would be " + emi + " per month.");
        System.out.print("Confirm? (y/n): ");
        if (!sc.nextLine().trim().equalsIgnoreCase("y")) return;
        Loan loan = bank.applyForLoan(no, amount, months);
        System.out.println("Loan approved and credited! " + loan);
    }

    private static void repayLoan(long no) throws BankException {
        List<Loan> active = bank.loansOf(no).stream().filter(l -> l.getStatus() == Loan.Status.ACTIVE).toList();
        if (active.isEmpty()) { System.out.println("No active loans."); return; }
        active.forEach(System.out::println);
        long id = readLong("Loan id to pay EMI for: ");
        BigDecimal paid = bank.repayLoan(no, id);
        System.out.println("Paid " + paid + ".");
        bank.loansOf(no).stream().filter(l -> l.getLoanId() == id).forEach(System.out::println);
    }

    // ============================ admin ============================
    private static void adminLogin() {
        System.out.print("Admin password: ");
        String pw = sc.nextLine();
        if (!PasswordUtil.hash(pw, ADMIN_SALT).equals(ADMIN_HASH)) {
            System.out.println("Wrong admin password.");
            return;
        }
        while (true) {
            System.out.println("\n--- ADMIN ---"
                    + "\n 1. List all accounts      2. Freeze account       3. Unfreeze account"
                    + "\n 4. Total bank balance     5. Credit monthly interest now"
                    + "\n SQL reports (need MySQL):"
                    + "\n 6. Balance by account type (GROUP BY)   7. Top accounts (VIEW)"
                    + "\n 8. Daily transaction summary            9. Loan overview (VIEW + JOIN)"
                    + "\n10. Audit log (written by a TRIGGER)    11. Search holder name (LIKE)"
                    + "\n 0. Back");
            int c = readInt("Choose: ");
            try {
                switch (c) {
                    case 1 -> bank.allAccounts().forEach(System.out::println);
                    case 2 -> { bank.setFrozen(readLong("Account number: "), true); System.out.println("Frozen."); }
                    case 3 -> { bank.setFrozen(readLong("Account number: "), false); System.out.println("Unfrozen."); }
                    case 4 -> System.out.println("Total balance: " + bank.totalBankBalance());
                    case 5 -> System.out.println("Interest credited to " + bank.applyMonthlyInterest() + " account(s).");
                    case 6 -> showReport(JdbcReportRepository::balanceByAccountType);
                    case 7 -> showReport(r -> r.topAccounts(5));
                    case 8 -> showReport(JdbcReportRepository::dailyTransactionSummary);
                    case 9 -> showReport(JdbcReportRepository::loanOverview);
                    case 10 -> showReport(r -> r.auditLog(15));
                    case 11 -> {
                        System.out.print("Name contains: ");
                        String kw = sc.nextLine().trim();
                        showReport(r -> r.searchHolders(kw));
                    }
                    case 0 -> { return; }
                    default -> System.out.println("Invalid option.");
                }
            } catch (BankException e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static void showReport(ReportCall call) {
        if (reports == null) {
            System.out.println("This report needs the MySQL database (currently running in memory).");
            return;
        }
        try {
            call.run(reports).forEach(System.out::println);
        } catch (PersistenceException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    // ============================ safe input helpers ============================
    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return Integer.parseInt(sc.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.println("Enter a valid number."); }
        }
    }

    private static long readLong(String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return Long.parseLong(sc.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.println("Enter a valid number."); }
        }
    }

    private static BigDecimal readMoney(String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return new BigDecimal(sc.nextLine().trim()); }
            catch (NumberFormatException e) { System.out.println("Enter a valid amount."); }
        }
    }

    private static LocalDate readDate(String prompt) {
        while (true) {
            System.out.print(prompt);
            try { return LocalDate.parse(sc.nextLine().trim()); }
            catch (DateTimeParseException e) { System.out.println("Use the format yyyy-mm-dd."); }
        }
    }
}
