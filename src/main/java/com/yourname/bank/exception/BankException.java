package com.yourname.bank.exception;

/** Base class for all checked business exceptions in the bank. */
public class BankException extends Exception {
    public BankException(String message) { super(message); }
}
