package com.yourname.bank.exception;

/** Thrown when saving to or loading from the database fails. */
public class PersistenceException extends BankException {
    public PersistenceException(String message, Throwable cause) {
        super(message);
        if (cause != null) initCause(cause);
    }
}
