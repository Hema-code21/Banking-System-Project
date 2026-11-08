package com.yourname.bank;

import com.yourname.bank.exception.*;
import com.yourname.bank.model.Account;
import com.yourname.bank.model.AccountType;
import com.yourname.bank.model.Loan;
import com.yourname.bank.model.Transaction;
import com.yourname.bank.repository.InMemoryAccountRepository;
import com.yourname.bank.service.BankService;
import com.yourname.bank.service.InterestScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BankFeaturesTest {
    private BankService bank;
    private Account savings;
    private Account current;

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @BeforeEach
    void setUp() throws Exception {
        bank = new BankService();
        savings = bank.createAccount(AccountType.SAVINGS, "Asha", "1234", bd("10000"));
        current = bank.createAccount(AccountType.CURRENT, "Ravi", "4321", bd("2000"));
    }

    // ---------- loans ----------
    @Test
    void emiMatchesStandardFormula() {
        // 100000 at 12% p.a. for 12 months -> 8884.88 (well-known textbook value)
        assertEquals(0, bd("8884.88").compareTo(Loan.calculateEmi(bd("100000"), bd("12"), 12)));
    }

    @Test
    void loanIsCreditedToAccount() throws Exception {
        Loan loan = bank.applyForLoan(current.getAccountNumber(), bd("50000"), 12);
        assertEquals(Loan.Status.ACTIVE, loan.getStatus());
        assertEquals(0, bd("52000").compareTo(current.getBalance()));
        assertEquals(1, bank.loansOf(current.getAccountNumber()).size());
    }

    @Test
    void loanIsClosedAfterAllEmisArePaid() throws Exception {
        Loan loan = bank.applyForLoan(current.getAccountNumber(), bd("12000"), 6);
        for (int i = 0; i < 6; i++) bank.repayLoan(current.getAccountNumber(), loan.getLoanId());
        assertEquals(Loan.Status.CLOSED, loan.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(loan.getOutstanding()));
        assertThrows(LoanException.class, () -> bank.repayLoan(current.getAccountNumber(), loan.getLoanId()));
    }

    @Test
    void secondActiveLoanIsRejected() throws Exception {
        bank.applyForLoan(current.getAccountNumber(), bd("5000"), 12);
        assertThrows(LoanException.class, () -> bank.applyForLoan(current.getAccountNumber(), bd("5000"), 12));
    }

    @Test
    void loanAmountAndTenureLimitsAreEnforced() {
        assertThrows(LoanException.class, () -> bank.applyForLoan(current.getAccountNumber(), bd("10"), 12));
        assertThrows(LoanException.class, () -> bank.applyForLoan(current.getAccountNumber(), bd("5000"), 2));
    }

    @Test
    void cannotRepaySomeoneElsesLoan() throws Exception {
        Loan loan = bank.applyForLoan(current.getAccountNumber(), bd("5000"), 12);
        assertThrows(LoanException.class, () -> bank.repayLoan(savings.getAccountNumber(), loan.getLoanId()));
    }

    // ---------- interest ----------
    @Test
    void monthlyInterestIsCreditedOnlyToSavings() throws Exception {
        int credited = bank.applyMonthlyInterest();
        assertEquals(1, credited);
        assertEquals(0, bd("10033.33").compareTo(savings.getBalance()));   // 10000 * 4% / 12
        assertEquals(0, bd("2000").compareTo(current.getBalance()));
        assertEquals(Transaction.Type.INTEREST, savings.getHistory().get(0).type());
    }

    @Test
    void schedulerCreditsInterestInBackground() throws Exception {
        InterestScheduler scheduler = new InterestScheduler(bank);
        scheduler.start(0, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(450);
        scheduler.stop();
        assertTrue(savings.getHistory().size() >= 2, "expected several interest credits");
    }

    // ---------- freeze ----------
    @Test
    void frozenAccountRejectsMoneyOperations() throws Exception {
        bank.setFrozen(savings.getAccountNumber(), true);
        assertThrows(AccountFrozenException.class, () -> bank.deposit(savings.getAccountNumber(), bd("10")));
        assertThrows(AccountFrozenException.class,
                () -> bank.transfer(current.getAccountNumber(), savings.getAccountNumber(), bd("10")));
        bank.setFrozen(savings.getAccountNumber(), false);
        bank.deposit(savings.getAccountNumber(), bd("10"));
    }

    // ---------- login security ----------
    @Test
    void threeWrongPinsLockLogin() throws Exception {
        long no = savings.getAccountNumber();
        assertThrows(AuthenticationException.class, () -> bank.login(no, "0000"));
        assertThrows(AuthenticationException.class, () -> bank.login(no, "0000"));
        assertThrows(AuthenticationException.class, () -> bank.login(no, "0000"));
        AuthenticationException e = assertThrows(AuthenticationException.class, () -> bank.login(no, "1234"));
        assertTrue(e.getMessage().contains("Try again"));
    }

    @Test
    void changePinWorksAndOldPinStopsWorking() throws Exception {
        long no = savings.getAccountNumber();
        bank.changePin(no, "1234", "9999");
        assertThrows(AuthenticationException.class, () -> bank.login(no, "1234"));
        assertNotNull(bank.login(no, "9999"));
    }

    @Test
    void changePinNeedsCorrectOldPinAndFourDigits() {
        long no = savings.getAccountNumber();
        assertThrows(AuthenticationException.class, () -> bank.changePin(no, "0000", "9999"));
        assertThrows(AuthenticationException.class, () -> bank.changePin(no, "1234", "12"));
    }

    @Test
    void invalidPinAtAccountCreationIsRejected() {
        assertThrows(AuthenticationException.class,
                () -> bank.createAccount(AccountType.SAVINGS, "Z", "abcd", bd("1000")));
    }

    // ---------- reports ----------
    @Test
    void totalsByTypeAndFilterByTypeWork() throws Exception {
        long no = current.getAccountNumber();
        bank.deposit(no, bd("100"));
        bank.deposit(no, bd("50"));
        bank.withdraw(no, bd("30"));
        assertEquals(0, bd("150").compareTo(bank.totalsByType(no).get(Transaction.Type.DEPOSIT)));
        assertEquals(2, bank.filterByType(no, Transaction.Type.DEPOSIT).size());
    }

    // ---------- persistence failure safety ----------
    @Test
    void failedLoanSaveLeavesNothingChanged() throws Exception {
        BankService failing = new BankService(new InMemoryAccountRepository() {
            @Override
            public long saveLoanDisbursal(Loan draft, Transaction credit) throws PersistenceException {
                throw new PersistenceException("DB down", null);
            }
        });
        Account a = failing.createAccount(AccountType.CURRENT, "Neha", "1234", bd("1000"));
        assertThrows(PersistenceException.class, () -> failing.applyForLoan(a.getAccountNumber(), bd("5000"), 12));
        assertEquals(0, bd("1000").compareTo(a.getBalance()));
        assertTrue(failing.loansOf(a.getAccountNumber()).isEmpty());
    }

    @Test
    void failedRepaymentSaveKeepsLoanAndBalanceUnchanged() throws Exception {
        final boolean[] failNow = {false};
        BankService svc = new BankService(new InMemoryAccountRepository() {
            @Override
            public void saveLoanRepayment(long id, BigDecimal out, Loan.Status st, Transaction t)
                    throws PersistenceException {
                if (failNow[0]) throw new PersistenceException("DB down", null);
            }
        });
        Account a = svc.createAccount(AccountType.CURRENT, "Neha", "1234", bd("1000"));
        Loan loan = svc.applyForLoan(a.getAccountNumber(), bd("6000"), 6);
        BigDecimal balanceBefore = a.getBalance();
        BigDecimal outstandingBefore = loan.getOutstanding();

        failNow[0] = true;
        assertThrows(PersistenceException.class, () -> svc.repayLoan(a.getAccountNumber(), loan.getLoanId()));
        assertEquals(0, balanceBefore.compareTo(a.getBalance()));
        assertEquals(0, outstandingBefore.compareTo(loan.getOutstanding()));
    }
}
