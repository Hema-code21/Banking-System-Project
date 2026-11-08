package com.yourname.bank.model;

import com.yourname.bank.exception.AccountFrozenException;
import com.yourname.bank.exception.InsufficientBalanceException;
import com.yourname.bank.exception.InvalidAmountException;
import com.yourname.bank.util.PasswordUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Abstract base class. Subclasses decide the lowest balance allowed (polymorphism).
 *
 * Money changes happen in two steps so the database can be updated first:
 *   1. prepareDeposit / prepareWithdraw  -> validates and builds a Transaction (no change yet)
 *   2. commit(transaction)               -> applies it to the balance and history
 * The caller must hold getLock() across both steps.
 */
public abstract class Account {
    private final long accountNumber;
    private final String holderName;
    private volatile String pinSalt;
    private volatile String pinHash;
    private volatile boolean frozen;

    protected BigDecimal balance;
    private final List<Transaction> history = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();

    protected Account(long accountNumber, String holderName,
                      String pinSalt, String pinHash, BigDecimal openingBalance) {
        this.accountNumber = accountNumber;
        this.holderName = holderName;
        this.pinSalt = pinSalt;
        this.pinHash = pinHash;
        this.balance = openingBalance;
    }

    /** Lowest balance this account type allows (negative means overdraft). */
    protected abstract BigDecimal minimumAllowedBalance();

    public abstract String getType();

    // ---------- step 1: prepare ----------
    public Transaction prepareDeposit(BigDecimal amount, Transaction.Type type)
            throws InvalidAmountException, AccountFrozenException {
        validate(amount);
        lock.lock();
        try {
            checkNotFrozen();
            return new Transaction(accountNumber, type, amount, balance.add(amount), LocalDateTime.now());
        } finally {
            lock.unlock();
        }
    }

    public Transaction prepareWithdraw(BigDecimal amount, Transaction.Type type)
            throws InvalidAmountException, InsufficientBalanceException, AccountFrozenException {
        validate(amount);
        lock.lock();
        try {
            checkNotFrozen();
            BigDecimal after = balance.subtract(amount);
            if (after.compareTo(minimumAllowedBalance()) < 0) {
                throw new InsufficientBalanceException(
                        "Insufficient balance. Available to withdraw: "
                                + balance.subtract(minimumAllowedBalance()));
            }
            return new Transaction(accountNumber, type, amount, after, LocalDateTime.now());
        } finally {
            lock.unlock();
        }
    }

    // ---------- step 2: commit ----------
    public void commit(Transaction t) {
        lock.lock();
        try {
            balance = t.balanceAfter();
            history.add(t);
        } finally {
            lock.unlock();
        }
    }

    /** Convenience for in-memory use: prepare + commit under one lock. */
    public void deposit(BigDecimal amount, Transaction.Type type)
            throws InvalidAmountException, AccountFrozenException {
        lock.lock();
        try { commit(prepareDeposit(amount, type)); } finally { lock.unlock(); }
    }

    public void withdraw(BigDecimal amount, Transaction.Type type)
            throws InvalidAmountException, InsufficientBalanceException, AccountFrozenException {
        lock.lock();
        try { commit(prepareWithdraw(amount, type)); } finally { lock.unlock(); }
    }

    /** Used when loading from the database: adds a past transaction without changing the balance. */
    public void restoreTransaction(Transaction t) {
        lock.lock();
        try { history.add(t); } finally { lock.unlock(); }
    }

    private void checkNotFrozen() throws AccountFrozenException {
        if (frozen) throw new AccountFrozenException(accountNumber);
    }

    private void validate(BigDecimal amount) throws InvalidAmountException {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero.");
        }
    }

    // ---------- PIN ----------
    public boolean verifyPin(String pin) {
        return PasswordUtil.hash(pin, pinSalt).equals(pinHash);
    }

    public void setPin(String newSalt, String newHash) {
        this.pinSalt = newSalt;
        this.pinHash = newHash;
    }

    // ---------- status ----------
    public boolean isFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }

    public BigDecimal getBalance() {
        lock.lock();
        try { return balance; } finally { lock.unlock(); }
    }

    public List<Transaction> getHistory() {
        lock.lock();
        try { return Collections.unmodifiableList(new ArrayList<>(history)); }
        finally { lock.unlock(); }
    }

    public ReentrantLock getLock() { return lock; }
    public long getAccountNumber() { return accountNumber; }
    public String getHolderName() { return holderName; }

    @Override
    public String toString() {
        return String.format("%-8s #%d | %-15s | balance %10s%s",
                getType(), accountNumber, holderName, getBalance(), frozen ? " | FROZEN" : "");
    }
}
