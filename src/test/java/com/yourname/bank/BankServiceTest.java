package com.yourname.bank;

import com.yourname.bank.exception.*;
import com.yourname.bank.model.Account;
import com.yourname.bank.model.Transaction;
import com.yourname.bank.repository.InMemoryAccountRepository;
import com.yourname.bank.service.BankService;
import com.yourname.bank.model.AccountType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BankServiceTest {
    private BankService bank;
    private Account savings;
    private Account current;

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @BeforeEach
    void setUp() throws Exception {
        bank = new BankService();
        savings = bank.createAccount(AccountType.SAVINGS, "Asha", "1234", bd("1000"));
        current = bank.createAccount(AccountType.CURRENT, "Ravi", "4321", bd("1000"));
    }

    @Test
    void depositIncreasesBalance() throws Exception {
        bank.deposit(savings.getAccountNumber(), bd("250"));
        assertEquals(0, bd("1250").compareTo(savings.getBalance()));
    }

    @Test
    void withdrawBelowMinimumBalanceFailsForSavings() {
        assertThrows(InsufficientBalanceException.class,
                () -> bank.withdraw(savings.getAccountNumber(), bd("600")));
    }

    @Test
    void currentAccountAllowsOverdraft() throws Exception {
        bank.withdraw(current.getAccountNumber(), bd("4000"));
        assertEquals(0, bd("-3000").compareTo(current.getBalance()));
    }

    @Test
    void negativeAmountIsRejected() {
        assertThrows(InvalidAmountException.class,
                () -> bank.deposit(savings.getAccountNumber(), bd("-5")));
    }

    @Test
    void unknownAccountThrows() {
        assertThrows(AccountNotFoundException.class, () -> bank.getAccount(99999));
    }

    @Test
    void transferMovesMoney() throws Exception {
        bank.transfer(current.getAccountNumber(), savings.getAccountNumber(), bd("300"));
        assertEquals(0, bd("700").compareTo(current.getBalance()));
        assertEquals(0, bd("1300").compareTo(savings.getBalance()));
    }

    @Test
    void transferToSameAccountFails() {
        assertThrows(InvalidAmountException.class,
                () -> bank.transfer(savings.getAccountNumber(), savings.getAccountNumber(), bd("10")));
    }

    @Test
    void loginWithWrongPinFails() {
        assertThrows(AuthenticationException.class, () -> bank.login(savings.getAccountNumber(), "0000"));
    }

    @Test
    void loginWithCorrectPinWorks() throws Exception {
        assertEquals(savings.getAccountNumber(), bank.login(savings.getAccountNumber(), "1234").getAccountNumber());
    }

    @Test
    void miniStatementReturnsLastNTransactions() throws Exception {
        for (int i = 0; i < 5; i++) bank.deposit(savings.getAccountNumber(), bd("10"));
        assertEquals(3, bank.miniStatement(savings.getAccountNumber(), 3).size());
    }

    @Test
    void concurrentOppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        BigDecimal before = bank.totalBankBalance();
        long a = savings.getAccountNumber(), b = current.getAccountNumber();

        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            final boolean forward = i % 2 == 0;
            fs.add(pool.submit(() -> {
                try { if (forward) bank.transfer(a, b, bd("1")); else bank.transfer(b, a, bd("1")); }
                catch (BankException ignored) { }
            }));
        }
        for (Future<?> f : fs) f.get(20, TimeUnit.SECONDS);   // would time out on deadlock
        pool.shutdown();

        assertEquals(0, before.compareTo(bank.totalBankBalance()));
    }

    @Test
    void failedDatabaseSaveLeavesMemoryUnchanged() throws Exception {
        BankService failing = new BankService(new InMemoryAccountRepository() {
            @Override
            public void saveTransactions(List<Transaction> txns) throws PersistenceException {
                throw new PersistenceException("DB down", null);
            }
        });
        Account a = failing.createAccount(AccountType.SAVINGS, "Neha", "1234", bd("1000"));

        assertThrows(PersistenceException.class, () -> failing.deposit(a.getAccountNumber(), bd("100")));

        assertEquals(0, bd("1000").compareTo(a.getBalance()));   // memory not changed
        assertTrue(a.getHistory().isEmpty());
    }
}
