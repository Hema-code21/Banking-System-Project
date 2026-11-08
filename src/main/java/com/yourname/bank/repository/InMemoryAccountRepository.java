package com.yourname.bank.repository;

import com.yourname.bank.exception.PersistenceException;
import com.yourname.bank.model.Account;
import com.yourname.bank.model.AccountType;
import com.yourname.bank.model.Loan;
import com.yourname.bank.model.Transaction;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Stores nothing; used for tests and as a fallback when MySQL is not available. */
public class InMemoryAccountRepository implements AccountRepository {
    private final AtomicLong nextAccount = new AtomicLong(1001);
    private final AtomicLong nextLoan = new AtomicLong(5001);

    @Override
    public long insertAccount(AccountType type, String holder, String pinSalt, String pinHash, BigDecimal opening)
            throws PersistenceException {
        return nextAccount.getAndIncrement();
    }

    @Override public void saveTransactions(List<Transaction> txns) throws PersistenceException { }
    @Override public void updateFrozen(long accountNumber, boolean frozen) throws PersistenceException { }
    @Override public void updatePin(long accountNumber, String s, String h) throws PersistenceException { }

    @Override
    public long saveLoanDisbursal(Loan draft, Transaction credit) throws PersistenceException {
        return nextLoan.getAndIncrement();
    }

    @Override
    public void saveLoanRepayment(long loanId, BigDecimal newOutstanding, Loan.Status newStatus, Transaction debit)
            throws PersistenceException { }

    @Override public List<Account> loadAll() throws PersistenceException { return Collections.emptyList(); }
    @Override public List<Loan> loadLoans() throws PersistenceException { return Collections.emptyList(); }
}
