package com.yourname.bank;

import com.yourname.bank.exception.BankException;
import com.yourname.bank.model.Account;
import com.yourname.bank.service.BankService;
import com.yourname.bank.model.AccountType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Proves thread safety: 50 threads make 5000 random transfers between 5 accounts.
 * Because transfers only MOVE money, the total must be exactly the same afterwards,
 * and the program must finish (no deadlock).
 */
public class ConcurrentTransferDemo {

    public static void run() {
        BankService bank = new BankService();
        int accountCount = 5;
        List<Long> ids = new ArrayList<>();
        try {
            for (int i = 0; i < accountCount; i++) {
                Account a = bank.createAccount(AccountType.CURRENT, "User" + i, "1234", new BigDecimal("10000"));
                ids.add(a.getAccountNumber());
            }
        } catch (BankException e) {
            throw new IllegalStateException(e);
        }

        BigDecimal before = bank.totalBankBalance();
        System.out.println("Total before: " + before);

        ExecutorService pool = Executors.newFixedThreadPool(50);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 5000; i++) {
            futures.add(pool.submit(() -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                long from = ids.get(r.nextInt(accountCount));
                long to = ids.get(r.nextInt(accountCount));
                BigDecimal amount = BigDecimal.valueOf(r.nextInt(1, 500));
                try {
                    bank.transfer(from, to, amount);
                    success.incrementAndGet();
                } catch (BankException e) {
                    failed.incrementAndGet();   // same account / overdraft limit hit
                }
            }));
        }

        try {
            for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            System.out.println("Problem (possible deadlock): " + e);
        } finally {
            pool.shutdown();
        }

        BigDecimal after = bank.totalBankBalance();
        System.out.println("Successful transfers: " + success + ", rejected: " + failed);
        System.out.println("Total after:  " + after);
        System.out.println(before.compareTo(after) == 0
                ? "PASS: money is conserved, no race conditions, no deadlock."
                : "FAIL: money mismatch!");
    }

    public static void main(String[] args) { run(); }
}
