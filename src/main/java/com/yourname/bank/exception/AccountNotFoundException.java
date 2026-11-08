package com.yourname.bank.exception;

public class AccountNotFoundException extends BankException {
    public AccountNotFoundException(long accountNumber) {
        super("Account not found: " + accountNumber);
    }
}
