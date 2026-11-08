package com.yourname.bank.exception;

public class AccountFrozenException extends BankException {
    public AccountFrozenException(long accountNumber) {
        super("Account " + accountNumber + " is frozen. Please contact the bank.");
    }
}
