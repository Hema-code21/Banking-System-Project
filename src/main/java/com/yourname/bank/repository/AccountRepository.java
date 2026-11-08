package com.yourname.bank.repository;

import com.yourname.bank.exception.PersistenceException;
import com.yourname.bank.model.Account;
import com.yourname.bank.model.AccountType;
import com.yourname.bank.model.Loan;
import com.yourname.bank.model.Transaction;

import java.math.BigDecimal;
import java.util.List;

/** Storage abstraction. BankService depends on this interface, not on JDBC directly. */
public interface AccountRepository {

    /** Stores a new account and returns the account number assigned to it. */
    long insertAccount(AccountType type, String holder, String pinSalt, String pinHash, BigDecimal opening)
            throws PersistenceException;

    /** Saves the given transactions and the new balances ATOMICALLY (all or nothing). */
    void saveTransactions(List<Transaction> txns) throws PersistenceException;

    void updateFrozen(long accountNumber, boolean frozen) throws PersistenceException;

    void updatePin(long accountNumber, String pinSalt, String pinHash) throws PersistenceException;

    /** Saves a new loan AND the money credited to the account in one DB transaction. Returns the loan id. */
    long saveLoanDisbursal(Loan draft, Transaction credit) throws PersistenceException;

    /** Saves an EMI payment: account balance, ledger row and loan balance in one DB transaction. */
    void saveLoanRepayment(long loanId, BigDecimal newOutstanding, Loan.Status newStatus, Transaction debit)
            throws PersistenceException;

    List<Account> loadAll() throws PersistenceException;

    List<Loan> loadLoans() throws PersistenceException;
}
